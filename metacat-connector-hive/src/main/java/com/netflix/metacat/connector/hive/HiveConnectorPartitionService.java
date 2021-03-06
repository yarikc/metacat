/*
 *  Copyright 2017 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */

package com.netflix.metacat.connector.hive;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.netflix.metacat.common.QualifiedName;
import com.netflix.metacat.common.dto.Pageable;
import com.netflix.metacat.common.dto.Sort;
import com.netflix.metacat.common.dto.SortOrder;
import com.netflix.metacat.common.server.connectors.ConnectorRequestContext;
import com.netflix.metacat.common.server.connectors.ConnectorPartitionService;
import com.netflix.metacat.common.server.connectors.ConnectorUtils;
import com.netflix.metacat.common.server.connectors.exception.ConnectorException;
import com.netflix.metacat.common.server.connectors.exception.InvalidMetaException;
import com.netflix.metacat.common.server.connectors.exception.PartitionAlreadyExistsException;
import com.netflix.metacat.common.server.connectors.exception.PartitionNotFoundException;
import com.netflix.metacat.common.server.connectors.exception.TableNotFoundException;
import com.netflix.metacat.common.server.connectors.model.PartitionInfo;
import com.netflix.metacat.common.server.connectors.model.PartitionListRequest;
import com.netflix.metacat.common.server.connectors.model.PartitionsSaveRequest;
import com.netflix.metacat.common.server.connectors.model.PartitionsSaveResponse;
import com.netflix.metacat.common.server.connectors.model.TableInfo;
import com.netflix.metacat.common.server.partition.util.PartitionUtil;
import com.netflix.metacat.connector.hive.converters.HiveConnectorInfoConverter;
import org.apache.hadoop.hive.metastore.Warehouse;
import org.apache.hadoop.hive.metastore.api.AlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.InvalidObjectException;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.thrift.TException;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * HiveConnectorPartitionService.
 *
 * @author zhenl
 * @since 1.0.0
 */
public class HiveConnectorPartitionService implements ConnectorPartitionService {
    protected final String catalogName;
    protected final HiveConnectorInfoConverter hiveMetacatConverters;
    private final IMetacatHiveClient metacatHiveClient;


    /**
     * Constructor.
     *
     * @param catalogName           catalogname
     * @param metacatHiveClient     hive client
     * @param hiveMetacatConverters hive converter
     */
    public HiveConnectorPartitionService(
        final String catalogName,
        final IMetacatHiveClient metacatHiveClient,
        final HiveConnectorInfoConverter hiveMetacatConverters) {
        this.metacatHiveClient = metacatHiveClient;
        this.hiveMetacatConverters = hiveMetacatConverters;
        this.catalogName = catalogName;
    }

    /**
     * {@inheritDoc}.
     */
    @Override
    public List<PartitionInfo> getPartitions(
        final ConnectorRequestContext requestContext,
        final QualifiedName tableName,
        final PartitionListRequest partitionsRequest
    ) {
        try {
            final List<Partition> partitions = getPartitions(tableName,
                partitionsRequest.getFilter(), partitionsRequest.getPartitionNames(),
                partitionsRequest.getSort(), partitionsRequest.getPageable());
            final Table table = metacatHiveClient.getTableByName(tableName.getDatabaseName(), tableName.getTableName());
            final TableInfo tableInfo = hiveMetacatConverters.toTableInfo(tableName, table);
            final List<PartitionInfo> partitionInfos = new ArrayList<>();
            for (Partition partition : partitions) {
                partitionInfos.add(hiveMetacatConverters.toPartitionInfo(tableInfo, partition));
            }
            return partitionInfos;
        } catch (NoSuchObjectException exception) {
            throw new TableNotFoundException(tableName, exception);
        } catch (MetaException | InvalidObjectException e) {
            throw new InvalidMetaException("Invalid metadata for " + tableName, e);
        } catch (TException e) {
            throw new ConnectorException(String.format("Failed get partitions for hive table %s", tableName), e);
        }
    }

    /**
     * {@inheritDoc}.
     */
    @Override
    public int getPartitionCount(
        final ConnectorRequestContext requestContext,
        final QualifiedName tableName
    ) {
        try {
            return metacatHiveClient.getPartitionCount(tableName.getDatabaseName(), tableName.getTableName());
        } catch (NoSuchObjectException exception) {
            throw new TableNotFoundException(tableName, exception);
        } catch (MetaException | InvalidObjectException e) {
            throw new InvalidMetaException("Invalid metadata for " + tableName, e);
        } catch (TException e) {
            throw new ConnectorException(String.format("Failed get partitions count for hive table %s", tableName), e);
        }
    }

    /**
     * {@inheritDoc}.
     */
    @Override
    public List<String> getPartitionKeys(
        final ConnectorRequestContext requestContext,
        final QualifiedName tableName,
        final PartitionListRequest partitionsRequest
    ) {
        final String filterExpression = partitionsRequest.getFilter();
        final List<String> partitionIds = partitionsRequest.getPartitionNames();
        List<String> names = Lists.newArrayList();
        final Pageable pageable = partitionsRequest.getPageable();
        try {
            if (filterExpression != null || (partitionIds != null && !partitionIds.isEmpty())) {
                final Table table = metacatHiveClient.getTableByName(tableName.getDatabaseName(),
                    tableName.getTableName());
                for (Partition partition : getPartitions(tableName, filterExpression,
                    partitionIds, partitionsRequest.getSort(), pageable)) {
                    names.add(getNameOfPartition(table, partition));
                }
            } else {
                names = metacatHiveClient.getPartitionNames(tableName.getDatabaseName(), tableName.getTableName());
                return ConnectorUtils.paginate(names, pageable);
            }
        } catch (NoSuchObjectException exception) {
            throw new TableNotFoundException(tableName, exception);
        } catch (MetaException | InvalidObjectException e) {
            throw new InvalidMetaException("Invalid metadata for " + tableName, e);
        } catch (TException e) {
            throw new ConnectorException(String.format("Failed get partitions keys for hive table %s", tableName), e);
        }
        return names;
    }

    private List<Partition> getPartitions(final QualifiedName tableName,
                                          @Nullable final String filter,
                                          @Nullable final List<String> partitionIds,
                                          @Nullable final Sort sort,
                                          @Nullable final Pageable pageable) {
        final String databasename = tableName.getDatabaseName();
        final String tablename = tableName.getTableName();
        try {
            final Table table = metacatHiveClient.getTableByName(databasename, tablename);
            List<Partition> partitionList = null;
            if (!Strings.isNullOrEmpty(filter)) {
                partitionList = metacatHiveClient.listPartitionsByFilter(databasename,
                    tablename, filter);
            } else {
                if (partitionIds != null) {
                    partitionList = metacatHiveClient.getPartitions(databasename,
                        tablename, partitionIds);
                }
                if (partitionList == null || partitionList.isEmpty()) {
                    partitionList = metacatHiveClient.getPartitions(databasename,
                        tablename, null);
                }
            }
            final List<Partition> filteredPartitionList = Lists.newArrayList();
            partitionList.forEach(partition -> {
                final String partitionName = getNameOfPartition(table, partition);
                if (partitionIds == null || partitionIds.contains(partitionName)) {
                    filteredPartitionList.add(partition);
                }
            });
            if (sort != null) {
                if (sort.getOrder() == SortOrder.DESC) {
                    Collections.sort(filteredPartitionList, Collections.reverseOrder());
                } else {
                    Collections.sort(filteredPartitionList);
                }
            }
            return ConnectorUtils.paginate(filteredPartitionList, pageable);
        } catch (NoSuchObjectException exception) {
            throw new TableNotFoundException(tableName, exception);
        } catch (MetaException | InvalidObjectException e) {
            throw new InvalidMetaException("Invalid metadata for " + tableName, e);
        } catch (TException e) {
            throw new ConnectorException(String.format("Failed get partitions for hive table %s", tableName), e);
        }
    }

    /**
     * {@inheritDoc}.
     */
    @Override
    public List<String> getPartitionUris(
        final ConnectorRequestContext requestContext,
        final QualifiedName table,
        final PartitionListRequest partitionsRequest
    ) {
        final List<String> uris = Lists.newArrayList();
        for (Partition partition : getPartitions(table, partitionsRequest.getFilter(),
            partitionsRequest.getPartitionNames(), partitionsRequest.getSort(), partitionsRequest.getPageable())) {
            uris.add(partition.getSd().getLocation());
        }
        return uris;
    }

    /**
     * {@inheritDoc}.
     */
    @Override
    public PartitionsSaveResponse savePartitions(
        final ConnectorRequestContext requestContext,
        final QualifiedName tableName,
        final PartitionsSaveRequest partitionsSaveRequest
    ) {
        final String databasename = tableName.getDatabaseName();
        final String tablename = tableName.getTableName();
        // New partitions
        final List<Partition> hivePartitions = Lists.newArrayList();

        try {
            final Table table = metacatHiveClient.getTableByName(databasename, tablename);
            final List<PartitionInfo> partitionInfos = partitionsSaveRequest.getPartitions();
            // New partition ids
            final List<String> addedPartitionIds = Lists.newArrayList();
            // Updated partition ids
            final List<String> existingPartitionIds = Lists.newArrayList();
            // Existing partitions
            final List<Partition> existingHivePartitions = Lists.newArrayList();

            // Existing partition map
            Map<String, Partition> existingPartitionMap = Collections.emptyMap();

            if (partitionsSaveRequest.getCheckIfExists()) {
                final List<String> partitionNames = partitionInfos.stream().map(
                    partition -> {
                        final String partitionName = partition.getName().getPartitionName();
                        PartitionUtil
                            .validatePartitionName(partitionName,
                                getPartitionKeys(table.getPartitionKeys()));
                        return partitionName;
                    }).collect(Collectors.toList());
                existingPartitionMap = getPartitionsByNames(table, partitionNames);
            }

            final TableInfo tableInfo = hiveMetacatConverters.toTableInfo(tableName, table);

            for (PartitionInfo partitionInfo : partitionInfos) {
                final String partitionName = partitionInfo.getName().getPartitionName();
                final Partition hivePartition = existingPartitionMap.get(partitionName);
                if (hivePartition == null) {
                    addedPartitionIds.add(partitionName);
                    hivePartitions.add(hiveMetacatConverters.fromPartitionInfo(tableInfo, partitionInfo));
                } else {
                    //the partition exists, we should not do anything for the partition exists
                    //unless we alterifExists
                    if (partitionsSaveRequest.getAlterIfExists()) {
                        final Partition existingPartition =
                            hiveMetacatConverters.fromPartitionInfo(tableInfo, partitionInfo);
                        existingPartitionIds.add(partitionName);
                        existingPartition.setParameters(hivePartition.getParameters());
                        existingPartition.setCreateTime(hivePartition.getCreateTime());
                        existingPartition.setLastAccessTime(hivePartition.getLastAccessTime());
                        existingHivePartitions.add(existingPartition);
                    }
                }
            }

            final Set<String> deletePartitionIds = Sets.newHashSet();
            if (!partitionsSaveRequest.getAlterIfExists()) {
                deletePartitionIds.addAll(existingPartitionIds);
            }
            if (partitionsSaveRequest.getPartitionIdsForDeletes() != null) {
                deletePartitionIds.addAll(partitionsSaveRequest.getPartitionIdsForDeletes());
            }

            if (partitionsSaveRequest.getAlterIfExists() && !existingHivePartitions.isEmpty()) {
                copyTableSdToPartitionSd(existingHivePartitions, table);
                metacatHiveClient.alterPartitions(databasename,
                    tablename, existingHivePartitions);
            }

            copyTableSdToPartitionSd(hivePartitions, table);
            metacatHiveClient.addDropPartitions(databasename,
                tablename, hivePartitions, Lists.newArrayList(deletePartitionIds));

            final PartitionsSaveResponse result = new PartitionsSaveResponse();
            result.setAdded(addedPartitionIds);
            result.setUpdated(existingPartitionIds);
            return result;
        } catch (NoSuchObjectException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("Partition doesn't exist")) {
                throw new PartitionNotFoundException(tableName, "", exception);
            } else {
                throw new TableNotFoundException(tableName, exception);
            }
        } catch (MetaException | InvalidObjectException exception) {
            throw new InvalidMetaException("One or more partitions are invalid.", exception);
        } catch (AlreadyExistsException e) {
            final List<String> ids = getFakePartitionName(hivePartitions);
            throw new PartitionAlreadyExistsException(tableName, ids, e);
        } catch (TException exception) {
            throw new ConnectorException(String.format("Failed savePartitions hive table %s", tableName), exception);
        }
    }

    private List<String> getFakePartitionName(final List<Partition> partitions) {
        // Since we would have to do a table load to find out the actual partition keys here we make up fake ones
        // so that we can generate the partition name
        final List<String> ids = partitions.stream().map(Partition::getValues).map(values -> {
            final Map<String, String> spec = new LinkedHashMap<>();
            for (int i = 0; i < values.size(); i++) {
                spec.put("fakekey" + i, values.get(i));
            }
            try {
                return Warehouse.makePartPath(spec);
            } catch (MetaException me) {
                return "Got: '" + me.getMessage() + "' for spec: " + spec;
            }
        }).collect(Collectors.toList());

        return ids;
    }

    /**
     * {@inheritDoc}.
     */
    @Override
    public void deletePartitions(
        final ConnectorRequestContext requestContext,
        final QualifiedName tableName,
        final List<String> partitionNames
    ) {

        try {
            metacatHiveClient.dropPartitions(tableName.getDatabaseName(), tableName.getTableName(), partitionNames);
        } catch (MetaException | NoSuchObjectException exception) {
            throw new TableNotFoundException(tableName, exception);
        } catch (InvalidObjectException e) {
            throw new InvalidMetaException("One or more partitions are invalid.", e);
        } catch (TException e) {
            //not sure which qualified name to use here
            throw new ConnectorException(String.format("Failed delete partitions for hive table %s", tableName), e);
        }

    }

    /**
     * get name of partitions.
     *
     * @param table
     * @param partition
     * @return partition name
     */
    String getNameOfPartition(final Table table, final Partition partition) {
        try {
            return Warehouse.makePartName(table.getPartitionKeys(), partition.getValues());
        } catch (TException e) {
            throw new InvalidMetaException("One or more partition names are invalid.", e);
        }
    }

    /**
     * Returns the list of partition keys.
     *
     * @param fields fields
     * @return partition keys
     */
    protected List<String> getPartitionKeys(final List<FieldSchema> fields) {
        final List<String> result = Lists.newArrayList();
        if (fields != null) {
            result.addAll(fields.stream().map(FieldSchema::getName).collect(Collectors.toList()));
        }
        return result;
    }


    protected Map<String, Partition> getPartitionsByNames(final Table table, final List<String> partitionNames)
        throws TException {
        final String databasename = table.getDbName();
        final String tablename = table.getTableName();
        List<Partition> partitions =
            metacatHiveClient.getPartitions(databasename, tablename, partitionNames);
        if (partitions == null || partitions.isEmpty()) {
            if (partitionNames == null || partitionNames.isEmpty()) {
                return Collections.emptyMap();
            }

            // Fall back to scanning all partitions ourselves if finding by name does not work
            final List<Partition> allPartitions =
                metacatHiveClient.getPartitions(databasename, tablename,
                    null);
            if (allPartitions == null || allPartitions.isEmpty()) {
                return Collections.emptyMap();
            }

            partitions = allPartitions.stream().filter(part -> {
                try {
                    return partitionNames.contains(
                        Warehouse.makePartName(table.getPartitionKeys(), part.getValues()));
                } catch (Exception e) {
                    throw new InvalidMetaException("One or more partition names are invalid.", e);
                }
            }).collect(Collectors.toList());
        }

        return partitions.stream().collect(Collectors.toMap(part -> {
            try {
                return Warehouse.makePartName(table.getPartitionKeys(), part.getValues());
            } catch (Exception e) {
                throw new InvalidMetaException("One or more partition names are invalid.", e);
            }
        }, Function.identity()));
    }

    private void copyTableSdToPartitionSd(final List<Partition> hivePartitions, final Table table) {
        //
        // Update the partition info based on that of the table.
        //
        for (Partition partition : hivePartitions) {
            final StorageDescriptor sd = partition.getSd();
            final StorageDescriptor tableSdCopy = table.getSd().deepCopy();
            if (tableSdCopy.getSerdeInfo() == null) {
                final SerDeInfo serDeInfo = new SerDeInfo(null, null, new HashMap<>());
                tableSdCopy.setSerdeInfo(serDeInfo);
            }

            tableSdCopy.setLocation(sd.getLocation());
            if (!Strings.isNullOrEmpty(sd.getInputFormat())) {
                tableSdCopy.setInputFormat(sd.getInputFormat());
            }
            if (!Strings.isNullOrEmpty(sd.getOutputFormat())) {
                tableSdCopy.setOutputFormat(sd.getOutputFormat());
            }
            if (sd.getParameters() != null && !sd.getParameters().isEmpty()) {
                tableSdCopy.setParameters(sd.getParameters());
            }
            if (sd.getSerdeInfo() != null) {
                if (!Strings.isNullOrEmpty(sd.getSerdeInfo().getName())) {
                    tableSdCopy.getSerdeInfo().setName(sd.getSerdeInfo().getName());
                }
                if (!Strings.isNullOrEmpty(sd.getSerdeInfo().getSerializationLib())) {
                    tableSdCopy.getSerdeInfo().setSerializationLib(sd.getSerdeInfo().getSerializationLib());
                }
                if (sd.getSerdeInfo().getParameters() != null && !sd.getSerdeInfo().getParameters().isEmpty()) {
                    tableSdCopy.getSerdeInfo().setParameters(sd.getSerdeInfo().getParameters());
                }
            }
            partition.setSd(tableSdCopy);
        }
    }
}
