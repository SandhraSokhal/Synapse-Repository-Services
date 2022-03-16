package org.sagebionetworks.table.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sagebionetworks.repo.model.util.AccessControlListUtil.createResourceAccess;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.AsynchronousJobWorkerHelper;
import org.sagebionetworks.AsynchronousJobWorkerHelperImpl.AsyncJobResponse;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.table.ColumnModelManager;
import org.sagebionetworks.repo.manager.table.MaterializedViewManager;
import org.sagebionetworks.repo.manager.table.TableEntityManager;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.manager.table.TableViewManager;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AsynchJobFailedException;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.Entity;
import org.sagebionetworks.repo.model.FileEntity;
import org.sagebionetworks.repo.model.Folder;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.Project;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.annotation.v2.Annotations;
import org.sagebionetworks.repo.model.annotation.v2.AnnotationsV2TestUtils;
import org.sagebionetworks.repo.model.annotation.v2.AnnotationsValueType;
import org.sagebionetworks.repo.model.auth.NewUser;
import org.sagebionetworks.repo.model.dbo.dao.table.TableModelTestUtils;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.helper.AccessControlListObjectHelper;
import org.sagebionetworks.repo.model.helper.FileHandleObjectHelper;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.table.AppendableRowSetRequest;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.EntityView;
import org.sagebionetworks.repo.model.table.MaterializedView;
import org.sagebionetworks.repo.model.table.ObjectField;
import org.sagebionetworks.repo.model.table.PartialRow;
import org.sagebionetworks.repo.model.table.PartialRowSet;
import org.sagebionetworks.repo.model.table.ReplicationType;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowReferenceSet;
import org.sagebionetworks.repo.model.table.RowReferenceSetResults;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.sagebionetworks.repo.model.table.SnapshotRequest;
import org.sagebionetworks.repo.model.table.TableConstants;
import org.sagebionetworks.repo.model.table.TableEntity;
import org.sagebionetworks.repo.model.table.TableUpdateTransactionRequest;
import org.sagebionetworks.repo.model.table.TableUpdateTransactionResponse;
import org.sagebionetworks.repo.model.table.ViewEntityType;
import org.sagebionetworks.repo.model.table.ViewScope;
import org.sagebionetworks.repo.model.table.ViewTypeMask;
import org.sagebionetworks.table.cluster.utils.TableModelUtils;
import org.sagebionetworks.util.Pair;
import org.sagebionetworks.util.TimeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.google.common.collect.Lists;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:test-context.xml" })
public class MaterializedViewUpdateWorkerIntegrationTest {
	
	public static final Long MAX_WAIT_MS = 30_000L;

	@Autowired
	private TableManagerSupport tableManagerSupport;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private UserManager userManager;

	@Autowired
	private FileHandleObjectHelper fileHandleObjectHelper;
	
	@Autowired
	private AccessControlListObjectHelper aclDaoHelper;

	@Autowired
	private ColumnModelManager columnModelManager;

	@Autowired
	private TableViewManager tableViewManager;
	
	@Autowired
	private AsynchronousJobWorkerHelper asyncHelper;
	
	@Autowired
	private MaterializedViewManager materializedViewManager;
	
	@Autowired
	private TableEntityManager tableManager;

	private UserInfo adminUserInfo;
	private UserInfo userInfo;

	@BeforeEach
	public void before() {
		aclDaoHelper.truncateAll();
		entityManager.truncateAll();
		
		adminUserInfo = userManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());

		boolean acceptsTermsOfUse = true;
		String userName = UUID.randomUUID().toString();
		userInfo = userManager.createOrGetTestUser(adminUserInfo,
				new NewUser().setUserName(userName).setEmail(userName + "@foo.org"), acceptsTermsOfUse);
	}

	@AfterEach
	public void after() {
		aclDaoHelper.truncateAll();
		entityManager.truncateAll();
	}

	@Test
	public void testMaterializedViewOfFileView() throws Exception {
		int numberOfFiles = 5;
		List<Entity> entites = createProjectHierachy(numberOfFiles);
		EntityView view = createEntityView(entites);
		
		Project project = entites.stream().filter(e -> e instanceof Project).map(e -> (Project) e).findFirst().get();
		Long projectId = KeyFactory.stringToKey(project.getId());
		// the user can only see files with the project as their benefactor.
		List<String> fileIdsUserCanSee = entites.stream()
				.filter((e) -> e instanceof FileEntity
						&& projectId.equals(entityManager.getEntityHeader(adminUserInfo, e.getId()).getBenefactorId()))
				.map(e -> e.getId()).collect(Collectors.toList());
		assertEquals(3, fileIdsUserCanSee.size());

		// Currently do not support doubles so the double key is excluded.
		String definingSql = "select id, stringKey, longKey, doubleKey, dateKey, booleanKey from " + view.getId();

		MaterializedView materializedView = entityManager.getEntity(
				adminUserInfo, entityManager.createEntity(adminUserInfo, new MaterializedView()
						.setName("aMaterializedView").setParentId(view.getParentId()).setDefiningSQL(definingSql), null),
				MaterializedView.class);
		materializedViewManager.registerSourceTables(IdAndVersion.parse(materializedView.getId()), definingSql);
		
		String finalSql = "select * from "+materializedView.getId()+" order by id asc";
		
		List<Row> expectedRows = Arrays.asList(
				new Row().setRowId(1L).setVersionNumber(0L).setValues(Arrays.asList(fileIdsUserCanSee.get(0), "a string: 3", "8", "6.140000000000001","1004", "false")),
				new Row().setRowId(3L).setVersionNumber(0L).setValues(Arrays.asList(fileIdsUserCanSee.get(1), "a string: 5", "10", "8.14", "1006", "false")),
				new Row().setRowId(5L).setVersionNumber(0L).setValues(Arrays.asList(fileIdsUserCanSee.get(2), "a string: 7", "12", "10.14", "1008", "false"))
		);
		
		// Wait for the query against the materialized view to have the expected results.
		asyncHelper.assertQueryResult(userInfo, finalSql, (results) -> {
			assertEquals(expectedRows, results.getQueryResult().getQueryResults().getRows());
		}, MAX_WAIT_MS);

	}
	
	@Test
	public void testTableSchemaChange() throws Exception {
		
		String projectId = createProject();
		
		List<ColumnModel> schema = Arrays.asList(
			columnModelManager.createColumnModel(adminUserInfo, new ColumnModel().setColumnType(ColumnType.STRING).setName("one")),
			columnModelManager.createColumnModel(adminUserInfo, new ColumnModel().setColumnType(ColumnType.INTEGER).setName("two"))
		);
		
		List<String> columnIds = TableModelUtils.getIds(schema);
		
		IdAndVersion tableId = createTable(projectId, columnIds);
		
		String sql = "SELECT * FROM " + tableId;
		
		// Wait for the table to build
		asyncHelper.assertQueryResult(adminUserInfo, sql, (r) -> {
			assertTrue(r.getQueryResult().getQueryResults().getRows().isEmpty());
		}, MAX_WAIT_MS);
				
		IdAndVersion materializedViewId = createMaterializedView(projectId, sql);
						
		// The columns are the same as the source table
		assertEquals(columnIds, columnModelManager.getColumnIdsForTable(materializedViewId));
		
		// Now simulate a schema change of the source table
		schema = Lists.newArrayList(
			columnModelManager.createColumnModel(adminUserInfo, new ColumnModel().setColumnType(ColumnType.INTEGER).setName("three"))
		);
		
		columnIds = TableModelUtils.getIds(schema);
		
		// This triggers the schema change and rebuild of the source table
		tableManager.tableUpdated(adminUserInfo, columnIds, tableId.toString(), false);
				
		final List<String> expectedColumnIds = columnIds;
		
		TimeUtils.waitFor(MAX_WAIT_MS, 1000, () -> {
			// The columns should eventually by aligned with the source table
			boolean sourceTableSynced = expectedColumnIds.equals(columnModelManager.getColumnIdsForTable(tableId));
			boolean materializedViewSynced = expectedColumnIds.equals(columnModelManager.getColumnIdsForTable(materializedViewId));
			return new Pair<>(sourceTableSynced && materializedViewSynced, null);
		});
		
	}
	
	@Test
	public void testMaterializedViewWithTableSource() throws Exception {
		String projectId = createProject();
		aclDaoHelper.update(projectId, ObjectType.ENTITY, a -> {
			a.getResourceAccess().add(createResourceAccess(userInfo.getId(), ACCESS_TYPE.READ));
		});
		
		IdAndVersion tableId = createTable(projectId);
		
		String definingSql = "select * from "+tableId.toString();
		
		IdAndVersion materializedViewId = createMaterializedView(projectId, definingSql);
		
		List<Row> expectedRows = Arrays.asList(
				new Row().setRowId(1L).setVersionNumber(0L).setValues(Arrays.asList("string0", "103000", "682003.12", "false")),
				new Row().setRowId(2L).setVersionNumber(0L).setValues(Arrays.asList("string1", "103001", "682006.53", "true"))
		);
		
		String finalSql = "select * from "+materializedViewId.toString()+" order by ROW_ID asc";
		
		// The user does not have download on the dependent table.
		String message = assertThrows(UnauthorizedException.class, ()->{
			asyncHelper.assertQueryResult(userInfo, finalSql, (results) -> {
				assertEquals(expectedRows, results.getQueryResult().getQueryResults().getRows());
			}, MAX_WAIT_MS);
		}).getMessage();
		assertEquals("You lack DOWNLOAD access to the requested entity.", message);
		
		// grant the user download on the table.
		aclDaoHelper.update(projectId, ObjectType.ENTITY, a -> {
			a.getResourceAccess().add(createResourceAccess(userInfo.getId(), ACCESS_TYPE.DOWNLOAD));
		});
		
		asyncHelper.assertQueryResult(userInfo, finalSql, (results) -> {
			assertEquals(expectedRows, results.getQueryResult().getQueryResults().getRows());
		}, MAX_WAIT_MS);

	}
	
	/**
	 * This is a test for joining a view with a table.
	 * @throws Exception
	 */
	@Test
	public void testMaterializedViewWithJoins() throws Exception {
		int numberOfFiles = 5;
		List<Entity> entites = createProjectHierachy(numberOfFiles);
		String projectId = entites.get(0).getId();
		List<String> fileIds = entites.stream().filter((e) -> e instanceof FileEntity).map(e -> e.getId())
				.collect(Collectors.toList());
		assertEquals(numberOfFiles, fileIds.size());
		List<PatientData> patientData = Arrays.asList(
				new PatientData().withCode("abc").withPatientId(111L),
				new PatientData().withCode("def").withPatientId(222L)
		);
		IdAndVersion viewId = createFileViewWithPatientIds(entites, patientData);
		IdAndVersion tableId = createTableWithPatientIds(projectId, patientData);
		
		String definingSql = String.format(
				"select v.id, p.patientId, p.code from %s v join %s p on (v.patientId = p.patientId)",
				viewId.toString(), tableId.toString());
		
		IdAndVersion materializedViewId = createMaterializedView(projectId, definingSql);
		
		String materializedQuery = "select * from "+materializedViewId.toString()+" order by \"v.id\" asc";
		
		List<Row> expectedRows = Arrays.asList(
				new Row().setRowId(1L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(0), "111", "abc")),
				new Row().setRowId(2L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(1), "222", "def")),
				new Row().setRowId(3L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(2), "111", "abc")),
				new Row().setRowId(4L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(3), "222", "def")),
				new Row().setRowId(5L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(4), "111", "abc"))
		);
		
		// Wait for the query against the materialized view to have the expected results.
		asyncHelper.assertQueryResult(adminUserInfo, materializedQuery, (results) -> {
			assertEquals(expectedRows, results.getQueryResult().getQueryResults().getRows());
		}, MAX_WAIT_MS);

	}
	
	// Reproduce updates not propagating from source changes (See https://sagebionetworks.jira.com/browse/PLFM-6977)
	@Test
	public void testMaterializedViewWithJoinsAndPropagatedUpdate() throws Exception {
		int numberOfFiles = 2;
		List<Entity> entites = createProjectHierachy(numberOfFiles);
		String projectId = entites.get(0).getId();
		List<String> fileIds = entites.stream().filter((e) -> e instanceof FileEntity).map(e -> e.getId())
				.collect(Collectors.toList());
		assertEquals(numberOfFiles, fileIds.size());
		
		List<PatientData> patientData = Arrays.asList(
				new PatientData().withCode("abc").withPatientId(111L),
				new PatientData().withCode("def").withPatientId(222L)
		);
		
		IdAndVersion viewId = createFileViewWithPatientIds(entites, patientData);
		IdAndVersion tableId = createTableWithPatientIds(projectId, patientData);
		
		String definingSql = String.format(
				"select v.id, p.patientId, p.code from %s v join %s p on (v.patientId = p.patientId)",
				viewId.toString(), tableId.toString());
		
		IdAndVersion materializedViewId = createMaterializedView(projectId, definingSql);
		
		String materializedQuery = "select * from "+materializedViewId.toString()+" order by \"v.id\" asc";
		
		List<Row> expectedRows = Arrays.asList(
				new Row().setRowId(1L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(0), "111", "abc")),
				new Row().setRowId(2L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(1), "222", "def"))
		);
		
		// Wait for the query against the materialized view to have the expected results.
		asyncHelper.assertQueryResult(adminUserInfo, materializedQuery, (results) -> {
			assertEquals(expectedRows, results.getQueryResult().getQueryResults().getRows());
		}, MAX_WAIT_MS);
		
		// Now updates the dependent table
		RowSet tableRowSet = asyncHelper.assertQueryResult(adminUserInfo, "select * from "+tableId.toString(), (response) -> {
			assertEquals(patientData.size(), response.getQueryResult().getQueryResults().getRows().size());
		}, MAX_WAIT_MS).getQueryResult().getQueryResults();
		
		// Get the column model for the code
		SelectColumn codeColumn = tableRowSet.getHeaders().get(0);
		
		List<PartialRow> updatedRows = tableRowSet.getRows().stream().map( row -> 
			new PartialRow()
					.setRowId(row.getRowId())
					.setValues(Collections.singletonMap(codeColumn.getId(), row.getValues().get(0) + "_updated"))
		).collect(Collectors.toList());
		
		AppendableRowSetRequest request = new AppendableRowSetRequest()
				.setEntityId(tableId.toString())
				.setToAppend(new PartialRowSet()
						.setTableId(tableId.toString())
						.setRows(updatedRows));

		TableUpdateTransactionRequest txRequest = TableModelUtils.wrapInTransactionRequest(request);

		// Wait for the table update to complete
		asyncHelper.assertJobResponse(adminUserInfo, txRequest, (TableUpdateTransactionResponse response) -> {
			RowReferenceSetResults results = TableModelUtils.extractResponseFromTransaction(response, RowReferenceSetResults.class);
			assertEquals(updatedRows.size(), results.getRowReferenceSet().getRows().size());
		}, MAX_WAIT_MS);
		
		// Now verify that the dependent materialized view eventually gets updated
		asyncHelper.assertQueryResult(adminUserInfo, materializedQuery, (results) -> {
			results.getQueryResult().getQueryResults().getRows().forEach( row -> {
				String codeValue = row.getValues().get(row.getValues().size() - 1);
				assertTrue(codeValue.endsWith("_updated"));
			});
		}, MAX_WAIT_MS);

	}
	
	@Test
	public void testJoinViewAndTableSnapshots() throws Exception {
		int numberOfFiles = 5;
		List<Entity> entites = createProjectHierachy(numberOfFiles);
		String projectId = entites.get(0).getId();
		List<String> fileIds = entites.stream().filter((e) -> e instanceof FileEntity).map(e -> e.getId())
				.collect(Collectors.toList());
		assertEquals(numberOfFiles, fileIds.size());
		List<PatientData> patientData = Arrays.asList(
				new PatientData().withCode("abc").withPatientId(111L),
				new PatientData().withCode("def").withPatientId(222L)
		);
		IdAndVersion viewId = createFileViewWithPatientIds(entites, patientData);
		IdAndVersion viewSnapshotId = createSnapshot(viewId);
		IdAndVersion tableId = createTableWithPatientIds(projectId, patientData);
		IdAndVersion tableSnapshotId = createSnapshot(tableId);
		
		String definingSql = String.format(
				"select v.id as id, p.patientId as patient, p.code as code from %s v join %s p on (v.patientId = p.patientId)",
				viewSnapshotId.toString(), tableSnapshotId.toString());
		
		IdAndVersion materializedViewId = createMaterializedView(projectId, definingSql);
		
		String materializedQuery = "select * from "+materializedViewId.toString()+" order by id asc";
		
		List<Row> expectedRows = Arrays.asList(
				new Row().setRowId(1L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(0), "111", "abc")),
				new Row().setRowId(2L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(1), "222", "def")),
				new Row().setRowId(3L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(2), "111", "abc")),
				new Row().setRowId(4L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(3), "222", "def")),
				new Row().setRowId(5L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(4), "111", "abc"))
		);
		
		// Wait for the query against the materialized view to have the expected results.
		asyncHelper.assertQueryResult(adminUserInfo, materializedQuery, (results) -> {
			assertEquals(expectedRows, results.getQueryResult().getQueryResults().getRows());
		}, MAX_WAIT_MS);
	}
	
	@Test
	public void testMaterializedViewWithJoinMultipleViews() throws Exception {
		int numberOfFiles = 5;
		List<Entity> entites = createProjectHierachy(numberOfFiles);
		Project project = entites.stream().filter(e -> e instanceof Project).map(e -> (Project) e).findFirst().get();
		List<String> fileIds = entites.stream().filter((e) -> e instanceof FileEntity).map(e -> e.getId())
				.collect(Collectors.toList());
		assertEquals(numberOfFiles, fileIds.size());
		List<PatientData> patientData = Arrays.asList(
				new PatientData().withCode("abc").withPatientId(111L),
				new PatientData().withCode("def").withPatientId(222L)
		);
		IdAndVersion viewId = createFileViewWithPatientIds(entites, patientData);
		IdAndVersion folderview = createFolderViewWithPatientData(entites, patientData);
		
		String definingSql = String.format(
				"select v.id as id, p.patientId as patientId, p.code as code from %s v join %s p on (v.patientId = p.patientId)",
				viewId.toString(), folderview.toString());
		
		IdAndVersion materializedViewId = createMaterializedView(project.getId(), definingSql);
		
		String materializedQuery = "select * from "+materializedViewId.toString()+" order by id asc";
		
		List<Row> expectedRows = Arrays.asList(
				new Row().setRowId(1L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(0), "111", "abc")),
				new Row().setRowId(2L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(1), "222", "def")),
				new Row().setRowId(3L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(2), "111", "abc")),
				new Row().setRowId(4L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(3), "222", "def")),
				new Row().setRowId(5L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(4), "111", "abc"))
		);
		
		// Wait for the query against the materialized view to have the expected results.
		asyncHelper.assertQueryResult(adminUserInfo, materializedQuery, (results) -> {
			assertEquals(expectedRows, results.getQueryResult().getQueryResults().getRows());
		}, MAX_WAIT_MS);
		
		// run the query again as non-admin, this user cannot see one of the folders and two of the files.
		List<Row> expectedRowsNonAdmin = Arrays.asList(
				new Row().setRowId(1L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(0), "111", "abc")),
				new Row().setRowId(3L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(2), "111", "abc")),
				new Row().setRowId(5L).setVersionNumber(0L).setValues(Arrays.asList(fileIds.get(4), "111", "abc"))
		);
		
		// Wait for the query against the materialized view to have the expected results.
		asyncHelper.assertQueryResult(userInfo, materializedQuery, (results) -> {
			assertEquals(expectedRowsNonAdmin, results.getQueryResult().getQueryResults().getRows());
		}, MAX_WAIT_MS);
	}
	
	@Test
	public void testMaterializedViewWithViewDependencyAndGroupBy() throws Exception {
		int numberOfFiles = 5;
		List<Entity> entites = createProjectHierachy(numberOfFiles);
		String projectId = entites.get(0).getId();
		List<String> fileIds = entites.stream().filter((e) -> e instanceof FileEntity).map(e -> e.getId())
				.collect(Collectors.toList());
		assertEquals(numberOfFiles, fileIds.size());
		List<PatientData> patientData = Arrays.asList(
				new PatientData().withCode("abc").withPatientId(111L),
				new PatientData().withCode("def").withPatientId(222L)
		);
		IdAndVersion viewId = createFileViewWithPatientIds(entites, patientData);
		
		String definingSql = String.format(
				"select patientId, count(*) from %s group by patientId",
				viewId.toString());
		
		String message = assertThrows(IllegalArgumentException.class, ()->{
			createMaterializedView(projectId, definingSql);
		}).getMessage();
			
		assertEquals(message, TableConstants.DEFINING_SQL_WITH_GROUP_BY_ERROR);
	}
	
	/**
	 * Create a snapshot of the passed table/view.
	 * @param viewId
	 * @return The IdAndVersion of the resulting snapshot.
	 * @throws AssertionError
	 * @throws AsynchJobFailedException
	 */
	private IdAndVersion createSnapshot(IdAndVersion id) throws AssertionError, AsynchJobFailedException {

		TableUpdateTransactionRequest txRequest = new TableUpdateTransactionRequest().setEntityId(id.toString())
				.setSnapshotOptions(
						new SnapshotRequest().setSnapshotComment("snapshotting...").setSnapshotLabel("version two"))
				.setCreateSnapshot(true);

		AsyncJobResponse<TableUpdateTransactionResponse> wrapped = asyncHelper.assertJobResponse(adminUserInfo,
				txRequest, (TableUpdateTransactionResponse response) -> {
					assertNotNull(response.getSnapshotVersionNumber());
				}, MAX_WAIT_MS);
		return IdAndVersion.newBuilder().setId(id.getId())
				.setVersion(wrapped.getResponse().getSnapshotVersionNumber()).build();
	}
	
	public IdAndVersion createTable(String projectId) throws AssertionError, AsynchJobFailedException {

		List<ColumnModel> schema = Arrays.asList(
				columnModelManager.createColumnModel(adminUserInfo,
						new ColumnModel().setColumnType(ColumnType.STRING).setName("aString")),
				columnModelManager.createColumnModel(adminUserInfo,
						new ColumnModel().setColumnType(ColumnType.INTEGER).setName("anInteger")),
				columnModelManager.createColumnModel(adminUserInfo,
						new ColumnModel().setColumnType(ColumnType.DOUBLE).setName("aDouble")),
				columnModelManager.createColumnModel(adminUserInfo,
						new ColumnModel().setColumnType(ColumnType.BOOLEAN).setName("aBoolen")));

		List<String> columnIds = TableModelUtils.getIds(schema);

		IdAndVersion tableId = createTable(projectId, columnIds);
		String tableIdString = tableId.toString();
		
		int rowCount = 2;

		RowSet set = new RowSet().setRows(TableModelTestUtils.createRows(schema, rowCount)).setTableId(tableIdString)
				.setHeaders(TableModelUtils.getSelectColumns(schema));
		AppendableRowSetRequest request = new AppendableRowSetRequest().setEntityId(tableId.toString())
				.setEntityId(tableId.toString()).setToAppend(set);

		TableUpdateTransactionRequest txRequest = TableModelUtils.wrapInTransactionRequest(request);

		// Wait for the job to complete.
		asyncHelper.assertJobResponse(adminUserInfo, txRequest, (TableUpdateTransactionResponse response) -> {
			RowReferenceSetResults results = TableModelUtils.extractResponseFromTransaction(response,
					RowReferenceSetResults.class);
			assertNotNull(results.getRowReferenceSet());
			RowReferenceSet refSet = results.getRowReferenceSet();
			assertNotNull(refSet.getRows());
			assertEquals(rowCount, refSet.getRows().size());
		}, MAX_WAIT_MS);
		
		asyncHelper.assertQueryResult(adminUserInfo, "select * from "+tableIdString, (response) -> {
			assertEquals(rowCount, response.getQueryResult().getQueryResults().getRows().size());
		}, MAX_WAIT_MS);
		return tableId;
	}

	/**
	 * Helper to create an EntityView
	 * 
	 * @return
	 * @throws InterruptedException 
	 * @throws DatastoreException 
	 */
	public EntityView createEntityView(List<Entity> entites) throws DatastoreException, InterruptedException {

		Long viewTypeMask = ViewTypeMask.File.getMask();
		List<ColumnModel> schema = tableManagerSupport.getDefaultTableViewColumns(ViewEntityType.entityview,
				viewTypeMask);
		schema.add(new ColumnModel().setName("stringKey").setColumnType(ColumnType.STRING).setMaximumSize(50L));
		schema.add(new ColumnModel().setName("doubleKey").setColumnType(ColumnType.DOUBLE));
		schema.add(new ColumnModel().setName("longKey").setColumnType(ColumnType.INTEGER));
		schema.add(new ColumnModel().setName("dateKey").setColumnType(ColumnType.DATE));
		schema.add(new ColumnModel().setName("booleanKey").setColumnType(ColumnType.BOOLEAN));
		schema = columnModelManager.createColumnModels(adminUserInfo, schema);

		// Add annotations to each files
		for (int i = 0; i < entites.size(); i++) {
			Entity entity = entites.get(i);
			if (entity instanceof FileEntity) {
				FileEntity file = (FileEntity) entity;
				Annotations annos = entityManager.getAnnotations(adminUserInfo, file.getId());
				AnnotationsV2TestUtils.putAnnotations(annos, "stringKey", "a string: " + i,
						AnnotationsValueType.STRING);
				AnnotationsV2TestUtils.putAnnotations(annos, "doubleKey", Double.toString(3.14 + i),
						AnnotationsValueType.DOUBLE);
				AnnotationsV2TestUtils.putAnnotations(annos, "longKey", Long.toString(5 + i),
						AnnotationsValueType.LONG);
				AnnotationsV2TestUtils.putAnnotations(annos, "dateKey", Long.toString(1001 + i),
						AnnotationsValueType.TIMESTAMP_MS);
				AnnotationsV2TestUtils.putAnnotations(annos, "booleanKey", Boolean.toString(i % 2 == 0),
						AnnotationsValueType.BOOLEAN);
				entityManager.updateAnnotations(adminUserInfo, file.getId(), annos);
				file = entityManager.getEntity(adminUserInfo, file.getId(), FileEntity.class);
				// each file needs to be replicated.
				asyncHelper.waitForObjectReplication(ReplicationType.ENTITY, KeyFactory.stringToKey(file.getId()), file.getEtag(), MAX_WAIT_MS);
			}
		}
	
		return createView(entites.get(0).getId(), viewTypeMask, schema);
	}

	/**
	 * Helper to create a view.
	 * @param projectId
	 * @param viewTypeMask
	 * @param schema
	 * @return
	 */
	EntityView createView(String projectId, Long viewTypeMask, List<ColumnModel> schema) {
		List<String> scope = Arrays.asList(projectId);
		EntityView view = new EntityView();
		view.setName(UUID.randomUUID().toString());
		view.setParentId(projectId);
		view.setColumnIds(schema.stream().map(c -> c.getId()).collect(Collectors.toList()));
		view.setScopeIds(scope);
		view.setViewTypeMask(viewTypeMask);
		String viewId = entityManager.createEntity(adminUserInfo, view, null);
		view = entityManager.getEntity(adminUserInfo, viewId, EntityView.class);
		ViewScope viewScope = new ViewScope();
		viewScope.setViewEntityType(ViewEntityType.entityview);
		viewScope.setScope(view.getScopeIds());
		viewScope.setViewTypeMask(viewTypeMask);
		tableViewManager.setViewSchemaAndScope(adminUserInfo, view.getColumnIds(), viewScope, viewId);
		return view;
	}
	
	/**
	 * Helper to set the given patientIds on FileEntities in the passed list.  This will also wait for each entity to be replicated.
	 * @param entites
	 * @param patientIds
	 * @return
	 * @throws DatastoreException
	 * @throws InterruptedException
	 */
	public IdAndVersion createFileViewWithPatientIds(List<Entity> entites, List<PatientData> patientData) throws DatastoreException, InterruptedException {

		Long viewTypeMask = ViewTypeMask.File.getMask();

		List<ColumnModel> schema = Arrays.asList(
				new ColumnModel().setName(ObjectField.id.name()).setColumnType(ColumnType.ENTITYID),
				new ColumnModel().setName("patientId").setColumnType(ColumnType.INTEGER));

		schema = columnModelManager.createColumnModels(adminUserInfo, schema);

		int index = 0;
		for (Entity entity: entites) {
			if (entity instanceof FileEntity) {
				FileEntity file = (FileEntity) entity;
				Annotations annos = entityManager.getAnnotations(adminUserInfo, file.getId());
				// Odd files get the first patient, while even files get the second patient.
				AnnotationsV2TestUtils.putAnnotations(annos, "patientId", patientData.get(index%2).getPatientId().toString(),
						AnnotationsValueType.LONG);
				index++;
				entityManager.updateAnnotations(adminUserInfo, file.getId(), annos);
				file = entityManager.getEntity(adminUserInfo, file.getId(), FileEntity.class);
				// each file needs to be replicated.
				asyncHelper.waitForObjectReplication(ReplicationType.ENTITY, KeyFactory.stringToKey(file.getId()), file.getEtag(), MAX_WAIT_MS);
			}
		}

		EntityView view = createView(entites.get(0).getId(), viewTypeMask, schema);
		return KeyFactory.idAndVersion(view.getId(), null);
	}
	
	/**
	 * Helper to create a table of PatientData.
	 * @param projectId
	 * @param patientData
	 * @return
	 * @throws AssertionError
	 * @throws AsynchJobFailedException
	 */
	public IdAndVersion createTableWithPatientIds(String projectId, List<PatientData> patientData) throws AssertionError, AsynchJobFailedException {

		List<ColumnModel> schema = Arrays.asList(
				new ColumnModel().setName("code").setColumnType(ColumnType.STRING).setMaximumSize(50L),
				new ColumnModel().setName("patientId").setColumnType(ColumnType.INTEGER));

		schema = columnModelManager.createColumnModels(adminUserInfo, schema);

		List<String> columnIds = TableModelUtils.getIds(schema);

		IdAndVersion tableId = createTable(projectId, columnIds);
		String tableIdString = tableId.toString();
		
		List<Row> rows = patientData.stream().map(d->(new Row().setValues(Arrays.asList(
				d.getCode(),
				d.getPatientId().toString()
		)))).collect(Collectors.toList());
		
		RowSet set = new RowSet().setRows(rows).setTableId(tableIdString)
				.setHeaders(TableModelUtils.getSelectColumns(schema));
		AppendableRowSetRequest request = new AppendableRowSetRequest().setEntityId(tableId.toString())
				.setEntityId(tableId.toString()).setToAppend(set);

		TableUpdateTransactionRequest txRequest = TableModelUtils.wrapInTransactionRequest(request);

		// Wait for the job to complete.
		asyncHelper.assertJobResponse(adminUserInfo, txRequest, (TableUpdateTransactionResponse response) -> {
			RowReferenceSetResults results = TableModelUtils.extractResponseFromTransaction(response,
					RowReferenceSetResults.class);
			assertNotNull(results.getRowReferenceSet());
			RowReferenceSet refSet = results.getRowReferenceSet();
			assertNotNull(refSet.getRows());
			assertEquals(rows.size(), refSet.getRows().size());
		}, MAX_WAIT_MS);
		
		asyncHelper.assertQueryResult(adminUserInfo, "select * from "+tableIdString, (response) -> {
			assertEquals(rows.size(), response.getQueryResult().getQueryResults().getRows().size());
		}, MAX_WAIT_MS);
		return tableId;
	}
	
	/**
	 * Builds a folder view where each folder is annotated with the provided patientData.
	 * @param entites
	 * @param patientData
	 * @return
	 * @throws DatastoreException
	 * @throws InterruptedException
	 */
	public IdAndVersion createFolderViewWithPatientData(List<Entity> entites, List<PatientData> patientData) throws DatastoreException, InterruptedException {

		Long viewTypeMask = ViewTypeMask.Folder.getMask();

		List<ColumnModel> schema = Arrays.asList(
				new ColumnModel().setName("code").setColumnType(ColumnType.STRING).setMaximumSize(50L),
				new ColumnModel().setName("patientId").setColumnType(ColumnType.INTEGER));

		schema = columnModelManager.createColumnModels(adminUserInfo, schema);

		int index = 0;
		for (Entity entity: entites) {
			if (entity instanceof Folder) {
				Folder folder = (Folder) entity;
				Annotations annos = entityManager.getAnnotations(adminUserInfo, folder.getId());
				PatientData data = patientData.get(index);
				// Odd files get the first patient, while even files get the second patient.
				AnnotationsV2TestUtils.putAnnotations(annos, "patientId", data.getPatientId().toString(),
						AnnotationsValueType.LONG);
				AnnotationsV2TestUtils.putAnnotations(annos, "code", data.getCode(),
						AnnotationsValueType.STRING);
				index++;
				entityManager.updateAnnotations(adminUserInfo, folder.getId(), annos);
				folder = entityManager.getEntity(adminUserInfo, folder.getId(), Folder.class);
				// each file needs to be replicated.
				asyncHelper.waitForObjectReplication(ReplicationType.ENTITY, KeyFactory.stringToKey(folder.getId()), folder.getEtag(), MAX_WAIT_MS);
			}
		}

		EntityView view = createView(entites.get(0).getId(), viewTypeMask, schema);
		return KeyFactory.idAndVersion(view.getId(), null);
	}

	/**
	 * Helper to setup a file hierarchy.
	 * 
	 * @param numberOfFiles
	 * @return
	 */
	public List<Entity> createProjectHierachy(int numberOfFiles) {

		List<Entity> results = new ArrayList<>(numberOfFiles + 3);
		Project project = entityManager.getEntity(adminUserInfo, createProject(), Project.class);
		results.add(project);

		Folder folderOne = entityManager.getEntity(adminUserInfo, entityManager.createEntity(adminUserInfo,
				new Folder().setName("folder one").setParentId(project.getId()), null), Folder.class);
		results.add(folderOne);
		Folder folderTwo = entityManager.getEntity(adminUserInfo, entityManager.createEntity(adminUserInfo,
				new Folder().setName("folder two").setParentId(project.getId()), null), Folder.class);
		results.add(folderTwo);

		// grant the user read on the project
		aclDaoHelper.update(project.getId(), ObjectType.ENTITY, a -> {
			a.getResourceAccess().add(createResourceAccess(userInfo.getId(), ACCESS_TYPE.READ));
		});
		
		// add an ACL on folder two that does not grant the user read.
		aclDaoHelper.create(a -> {
			a.setId(folderTwo.getId());
			a.getResourceAccess().add(createResourceAccess(adminUserInfo.getId(), ACCESS_TYPE.CHANGE_PERMISSIONS));
			a.getResourceAccess().add(createResourceAccess(adminUserInfo.getId(), ACCESS_TYPE.CREATE));
			a.getResourceAccess().add(createResourceAccess(adminUserInfo.getId(), ACCESS_TYPE.UPDATE));
			a.getResourceAccess().add(createResourceAccess(adminUserInfo.getId(), ACCESS_TYPE.DELETE));
		});

		for (int i = 0; i < numberOfFiles; i++) {
			String parentId = i % 2 == 0 ? folderOne.getId() : folderTwo.getId();
			final int index = i;
			S3FileHandle fileHandle = fileHandleObjectHelper.createS3(f -> {
				f.setFileName("f" + index);
			});
			FileEntity file = entityManager
					.getEntity(adminUserInfo,
							entityManager.createEntity(adminUserInfo, new FileEntity().setName("file_" + index)
									.setParentId(parentId).setDataFileHandleId(fileHandle.getId()), null),
							FileEntity.class);
			results.add(file);
		}
		return results;
	}
	
	private String createProject() {
		return entityManager.createEntity(adminUserInfo, new Project().setName("A Project"), null);
	}
	
	private IdAndVersion createTable(String parentId, List<String> columnIds) {
		TableEntity table = new TableEntity();
		table.setName(UUID.randomUUID().toString());
		table.setParentId(parentId);
		table.setColumnIds(columnIds);
		
		String tableId = entityManager.createEntity(adminUserInfo, table, null);
		
		// Bind the schema. This is normally done at the service layer but the workers cannot depend on that layer.
		tableManager.tableUpdated(adminUserInfo, table.getColumnIds(), tableId, false);
		
		return KeyFactory.idAndVersion(tableId, null);
	}
	
	private IdAndVersion createMaterializedView(String parentId, String sql) {
		MaterializedView materializedView = new MaterializedView();
		
		materializedView.setName(UUID.randomUUID().toString());
		materializedView.setDefiningSQL(sql);
		materializedView.setParentId(parentId);
		
		String materializedViewId = entityManager.createEntity(adminUserInfo, materializedView, null);
		
		IdAndVersion idAndVersion = KeyFactory.idAndVersion(materializedViewId, null);
		
		// Bind the schema. This is normally done at the service layer but the workers cannot depend on that layer.
		materializedViewManager.registerSourceTables(idAndVersion, sql);
		
		return idAndVersion;
	}

	static class PatientData {
		Long patientId;
		String code;

		public PatientData withPatientId(Long patientId) {
			this.patientId = patientId;
			return this;
		}

		public PatientData withCode(String code) {
			this.code = code;
			return this;
		}

		public Long getPatientId() {
			return patientId;
		}

		public String getCode() {
			return code;
		}

		@Override
		public int hashCode() {
			return Objects.hash(code, patientId);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof PatientData)) {
				return false;
			}
			PatientData other = (PatientData) obj;
			return Objects.equals(code, other.code) && patientId == other.patientId;
		}
	}

}