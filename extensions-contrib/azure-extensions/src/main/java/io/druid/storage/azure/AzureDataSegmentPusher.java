/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.storage.azure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.microsoft.azure.storage.StorageException;
import io.druid.java.util.common.CompressionUtils;
import io.druid.java.util.common.StringUtils;
import io.druid.java.util.common.logger.Logger;
import io.druid.segment.SegmentUtils;
import io.druid.segment.loading.DataSegmentPusher;
import io.druid.timeline.DataSegment;
import org.joda.time.format.ISODateTimeFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

public class AzureDataSegmentPusher implements DataSegmentPusher
{

  private static final Logger log = new Logger(AzureDataSegmentPusher.class);
  private final AzureStorage azureStorage;
  private final AzureAccountConfig config;
  private final ObjectMapper jsonMapper;

  @Inject
  public AzureDataSegmentPusher(
      AzureStorage azureStorage,
      AzureAccountConfig config,
      ObjectMapper jsonMapper
  )
  {
    this.azureStorage = azureStorage;
    this.config = config;
    this.jsonMapper = jsonMapper;
  }

  @Deprecated
  @Override
  public String getPathForHadoop(String dataSource)
  {
    return getPathForHadoop();
  }

  @Override
  public String getPathForHadoop()
  {
    String hadoopPath = StringUtils.format(
        "%s://%s@%s.%s/",
        AzureDataSegmentPuller.AZURE_STORAGE_HADOOP_PROTOCOL,
        config.getContainer(),
        config.getAccount(),
        AzureDataSegmentPuller.AZURE_STORAGE_HOST_ADDRESS
    );

    log.info("Using Azure blob storage Hadoop path: %s", hadoopPath);

    return hadoopPath;
  }

  @Override
  public String getStorageDir(DataSegment dataSegment, boolean useUniquePath)
  {
    String seg = JOINER.join(
        dataSegment.getDataSource(),
        StringUtils.format(
            "%s_%s",
            // Use ISODateTimeFormat.basicDateTime() format, to avoid using colons in file path.
            dataSegment.getInterval().getStart().toString(ISODateTimeFormat.basicDateTime()),
            dataSegment.getInterval().getEnd().toString(ISODateTimeFormat.basicDateTime())
        ),
        dataSegment.getVersion().replace(":", "_"),
        dataSegment.getShardSpec().getPartitionNum(),
        useUniquePath ? DataSegmentPusher.generateUniquePath() : null
    );

    log.info("DataSegment: [%s]", seg);

    // Replace colons with underscores, since they are not supported through wasb:// prefix
    return seg;
  }

  @Override
  public List<String> getAllowedPropertyPrefixesForHadoop()
  {
    return ImmutableList.of("druid.azure");
  }

  public File createSegmentDescriptorFile(final ObjectMapper jsonMapper, final DataSegment segment) throws
                                                                                                    IOException
  {
    File descriptorFile = File.createTempFile("descriptor", ".json");
    try (FileOutputStream stream = new FileOutputStream(descriptorFile)) {
      stream.write(jsonMapper.writeValueAsBytes(segment));
    }

    return descriptorFile;
  }

  public Map<String, String> getAzurePaths(final DataSegment segment, final boolean useUniquePath)
  {
    final String storageDir = this.getStorageDir(segment, useUniquePath);

    return ImmutableMap.of(
        "index", StringUtils.format("%s/%s", storageDir, AzureStorageDruidModule.INDEX_ZIP_FILE_NAME),
        "descriptor", StringUtils.format("%s/%s", storageDir, AzureStorageDruidModule.DESCRIPTOR_FILE_NAME)
    );

  }

  public DataSegment uploadDataSegment(
      DataSegment segment,
      final int binaryVersion,
      final long size,
      final File compressedSegmentData,
      final File descriptorFile,
      final Map<String, String> azurePaths
  )
      throws StorageException, IOException, URISyntaxException
  {
    azureStorage.uploadBlob(compressedSegmentData, config.getContainer(), azurePaths.get("index"));
    azureStorage.uploadBlob(descriptorFile, config.getContainer(), azurePaths.get("descriptor"));

    final DataSegment outSegment = segment
        .withSize(size)
        .withLoadSpec(this.makeLoadSpec(new URI(azurePaths.get("index"))))
        .withBinaryVersion(binaryVersion);

    log.info("Deleting file [%s]", compressedSegmentData);
    compressedSegmentData.delete();

    log.info("Deleting file [%s]", descriptorFile);
    descriptorFile.delete();

    return outSegment;
  }

  @Override
  public DataSegment push(final File indexFilesDir, final DataSegment segment, final boolean useUniquePath)
      throws IOException
  {
    log.info("Uploading [%s] to Azure.", indexFilesDir);

    final int binaryVersion = SegmentUtils.getVersionFromDir(indexFilesDir);
    File zipOutFile = null;
    File descriptorFile = null;

    try {
      final File outFile = zipOutFile = File.createTempFile("index", ".zip");
      final long size = CompressionUtils.zip(indexFilesDir, zipOutFile);

      final File descFile = descriptorFile = createSegmentDescriptorFile(jsonMapper, segment);
      final Map<String, String> azurePaths = getAzurePaths(segment, useUniquePath);

      return AzureUtils.retryAzureOperation(
          () -> uploadDataSegment(segment, binaryVersion, size, outFile, descFile, azurePaths),
          config.getMaxTries()
      );
    }
    catch (Exception e) {
      throw Throwables.propagate(e);
    }
    finally {
      if (zipOutFile != null) {
        log.info("Deleting zipped index File[%s]", zipOutFile);
        zipOutFile.delete();
      }

      if (descriptorFile != null) {
        log.info("Deleting descriptor file[%s]", descriptorFile);
        descriptorFile.delete();
      }
    }
  }

  @Override
  public Map<String, Object> makeLoadSpec(URI uri)
  {
    return ImmutableMap.<String, Object>of(
        "type",
        AzureStorageDruidModule.SCHEME,
        "containerName",
        config.getContainer(),
        "blobPath",
        uri.toString()
    );
  }
}
