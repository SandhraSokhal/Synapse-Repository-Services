package org.sagebionetworks.repo.manager.table;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.sagebionetworks.common.util.progress.ProgressCallback;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.dbo.dao.table.MaterializedViewDao;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.MaterializedView;
import org.sagebionetworks.repo.model.table.TableConstants;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.table.cluster.SqlQuery;
import org.sagebionetworks.table.cluster.SqlQueryBuilder;
import org.sagebionetworks.table.query.ParseException;
import org.sagebionetworks.table.query.TableQueryParser;
import org.sagebionetworks.table.query.model.QuerySpecification;
import org.sagebionetworks.table.query.model.TableNameCorrelation;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MaterializedViewManagerImpl implements MaterializedViewManager {

	
	public static final String DEFAULT_ETAG = "DEFAULT";
	
	final private MaterializedViewDao dao;
	final private ColumnModelManager columModelManager;
	final private TableManagerSupport tableManagerSupport;
	final private TableIndexConnectionFactory connectionFactory;
	final private MaterializedViewDao materializedViewDao;

	@Autowired
	public MaterializedViewManagerImpl(MaterializedViewDao dao, ColumnModelManager columModelManager,
			TableManagerSupport tableManagerSupport, TableIndexConnectionFactory connectionFactory, MaterializedViewDao materializedViewDao) {
		this.dao = dao;
		this.columModelManager = columModelManager;
		this.tableManagerSupport = tableManagerSupport;
		this.connectionFactory = connectionFactory;
		this.materializedViewDao = materializedViewDao;
	}
	
	@Override
	public void validate(MaterializedView materializedView) {
		ValidateArgument.required(materializedView, "The materialized view");		

		getQuerySpecification(materializedView.getDefiningSQL()).getSingleTableName().orElseThrow(TableConstants.JOIN_NOT_SUPPORTED_IN_THIS_CONTEXT);
		
	}
	
	@Override
	@WriteTransaction
	public void registerSourceTables(IdAndVersion idAndVersion, String definingSql) {
		ValidateArgument.required(idAndVersion, "The id of the materialized view");
		
		QuerySpecification querySpecification = getQuerySpecification(definingSql);
		
		Set<IdAndVersion> newSourceTables = getSourceTableIds(querySpecification);
		Set<IdAndVersion> currentSourceTables = dao.getSourceTablesIds(idAndVersion);
		
		if (!newSourceTables.equals(currentSourceTables)) {
			Set<IdAndVersion> toDelete = new HashSet<>(currentSourceTables);
			
			toDelete.removeAll(newSourceTables);
			
			dao.deleteSourceTablesIds(idAndVersion, toDelete);
			dao.addSourceTablesIds(idAndVersion, newSourceTables);
		}
		
		bindSchemaToView(idAndVersion, querySpecification);
		tableManagerSupport.setTableToProcessingAndTriggerUpdate(idAndVersion);
	}
	
	/**
	 * Extract the schema from the defining query and bind the results to the provided materialized view.
	 * 
	 * @param idAndVersion
	 * @param definingQuery
	 */
	void bindSchemaToView(IdAndVersion idAndVersion, QuerySpecification definingQuery) {
		SqlQuery sqlQuery = new SqlQueryBuilder(definingQuery).schemaProvider(columModelManager).allowJoins(true)
				.build();
		bindSchemaToView(idAndVersion, sqlQuery);
	}
	
	void bindSchemaToView(IdAndVersion idAndVersion, SqlQuery sqlQuery) {
		// create each column as needed.
		List<String> schemaIds = sqlQuery.getSchemaOfSelect().stream()
				.map(c -> columModelManager.createColumnModel(c).getId()).collect(Collectors.toList());
		columModelManager.bindColumnsToVersionOfObject(schemaIds, idAndVersion);
	}
	
	static QuerySpecification getQuerySpecification(String definingSql) {
		ValidateArgument.requiredNotBlank(definingSql, "The definingSQL of the materialized view");
		try {
			return TableQueryParser.parserQuery(definingSql);
		} catch (ParseException e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}
	}
		
	static Set<IdAndVersion> getSourceTableIds(QuerySpecification querySpecification) {
		Set<IdAndVersion> sourceTableIds = new HashSet<>();
		
		for (TableNameCorrelation table : querySpecification.createIterable(TableNameCorrelation.class)) {
			sourceTableIds.add(IdAndVersion.parse(table.getTableName().toSql()));
		}
		
		return sourceTableIds;
	}

	@Override
	public List<String> getSchemaIds(IdAndVersion idAndVersion) {
		return columModelManager.getColumnIdsForTable(idAndVersion);
	}

	@Override
	public void deleteViewIndex(IdAndVersion idAndVersion) {
		TableIndexManager indexManager = connectionFactory.connectToTableIndex(idAndVersion);
		indexManager.deleteTableIndex(idAndVersion);
	}

	@Override
	public void createOrUpdateViewIndex(ProgressCallback callback, IdAndVersion idAndVersion) throws Exception {
		tableManagerSupport.tryRunWithTableExclusiveLock(callback, idAndVersion, (ProgressCallback innerCallback) -> {
			createOrRebuildViewHoldingExclusiveLock(innerCallback, idAndVersion);
			return null;
		});
	}
	
	void createOrRebuildViewHoldingExclusiveLock(ProgressCallback callback, IdAndVersion idAndVersion)
			throws Exception {
		
		String definingSql = materializedViewDao.getMaterializedViewDefiningSql(idAndVersion)
				.orElseThrow(() -> new IllegalArgumentException("No defining SQL for: " + idAndVersion.toString()));
		QuerySpecification querySpecification = getQuerySpecification(definingSql);
		SqlQuery sqlQuery = new SqlQueryBuilder(querySpecification).schemaProvider(columModelManager).allowJoins(true)
				.build();
		
		// schema of the current version is dynamic, while the schema of a snapshot is static.
		if(!idAndVersion.getVersion().isPresent()) {
			bindSchemaToView(idAndVersion, sqlQuery);
		}
		
		List<IdAndVersion> dependentTables = sqlQuery.getTableIds();
		IdAndVersion[] dependentArray = dependentTables.toArray(new IdAndVersion[dependentTables.size()]);
		// continue with a read lock on each dependent table.
		tableManagerSupport.tryRunWithTableNonexclusiveLock(callback, (ProgressCallback innerCallback) -> {
			createOrRebuildViewHoldingWriteLockAndAllDependentReadLocks(idAndVersion);
			return null;
		}, dependentArray);
	}

	void createOrRebuildViewHoldingWriteLockAndAllDependentReadLocks(IdAndVersion idAndVersion) {
//		try {
//			// Is the index out-of-synch?
//			if (!tableManagerSupport.isIndexWorkRequired(idAndVersion)) {
//				// nothing to do
//				return;
//			}
//		
//			// Start the worker
//			final String token = tableManagerSupport.startTableProcessing(idAndVersion);
//			TableIndexManager indexManager = connectionFactory.connectToTableIndex(idAndVersion);
//			// For now, always rebuild the materialized view from scratch.
//			indexManager.deleteTableIndex(idAndVersion);
//			
//			// Need the MD5 for the original schema.
//			String originalSchemaMD5Hex = tableManagerSupport.getSchemaMD5Hex(idAndVersion);
//			List<ColumnModel> viewSchema = columModelManager.getTableSchema(idAndVersion);
//
//			// create the table in the index.
//			boolean isTableView = true;
//			indexManager.setIndexSchema(idAndVersion, isTableView, viewSchema);
//			tableManagerSupport.attemptToUpdateTableProgress(idAndVersion, token, "Building MaterializedView...", 0L, 1L);
//			
//			Long viewCRC = null;
//			if(idAndVersion.getVersion().isPresent()) {
//				throw new UnsupportedOperationException("MaterializedView snapshots not currently supported");
//			}else {
//				viewCRC = buildMaterializedViewFromSources(idAndVersion, indexManager, viewSchema);
//			}
//			// now that table is created and populated the indices on the table can be
//			// optimized.
//			indexManager.optimizeTableIndices(idAndVersion);
//
//			//for any list columns, build separate tables that serve as an index
//			indexManager.populateListColumnIndexTables(idAndVersion, viewSchema);
//
//			// both the CRC and schema MD5 are used to determine if the view is up-to-date.
//			indexManager.setIndexVersionAndSchemaMD5Hex(idAndVersion, viewCRC, originalSchemaMD5Hex);
//			// Attempt to set the table to complete.
//			tableManagerSupport.attemptToSetTableStatusToAvailable(idAndVersion, token, DEFAULT_ETAG);
//		} catch (InvalidStatusTokenException e) {
//			throw new RecoverableMessageException(e);
//		} catch (Exception e) {
//			// failed.
//			tableManagerSupport.attemptToSetTableStatusToFailed(idAndVersion, e);
//			throw e;
//		}
	}

	/**
	 * 
	 * @param idAndVersion
	 * @param indexManager
	 * @param viewSchema
	 * @return
	 */
	Long buildMaterializedViewFromSources(IdAndVersion idAndVersion, TableIndexManager indexManager,
			List<ColumnModel> viewSchema) {
		// TODO Auto-generated method stub
		return null;
	}

}
