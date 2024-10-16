package org.sagebionetworks.repo.manager.file.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyListOf;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.EntityManager;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.table.TableQueryManager;
import org.sagebionetworks.repo.model.EntityChildrenRequest;
import org.sagebionetworks.repo.model.EntityChildrenResponse;
import org.sagebionetworks.repo.model.EntityHeader;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.NextPageToken;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dbo.file.download.BulkDownloadDAO;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.file.BatchFileRequest;
import org.sagebionetworks.repo.model.file.BatchFileResult;
import org.sagebionetworks.repo.model.file.DownloadList;
import org.sagebionetworks.repo.model.file.DownloadOrder;
import org.sagebionetworks.repo.model.file.DownloadOrderSummary;
import org.sagebionetworks.repo.model.file.DownloadOrderSummaryRequest;
import org.sagebionetworks.repo.model.file.DownloadOrderSummaryResponse;
import org.sagebionetworks.repo.model.file.ExternalFileHandle;
import org.sagebionetworks.repo.model.file.FileConstants;
import org.sagebionetworks.repo.model.file.FileHandleAssociateType;
import org.sagebionetworks.repo.model.file.FileHandleAssociation;
import org.sagebionetworks.repo.model.file.FileResult;
import org.sagebionetworks.repo.model.file.FileResultFailureCode;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.table.Query;
import org.sagebionetworks.repo.model.table.QueryBundleRequest;
import org.sagebionetworks.repo.model.table.QueryResult;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.TableUnavailableException;
import org.sagebionetworks.table.query.ParseException;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.sagebionetworks.workers.util.semaphore.LockType;
import org.sagebionetworks.workers.util.semaphore.LockUnavilableException;

import com.google.common.collect.Lists;

@ExtendWith(MockitoExtension.class)
public class BulkDownloadManagerImplTest {

	@Mock
	EntityManager mockEntityManager;

	@Mock
	NodeDAO mockNodeDao;

	@Mock
	BulkDownloadDAO mockBulkDownloadDao;

	@Mock
	TableQueryManager mockTableQueryManager;

	@Mock
	FileHandleManager mockFileHandlerManager;
	
	@Mock
	ProgressCallback mockProgressCallback;

	@InjectMocks
	BulkDownloadManagerImpl manager;

	@Captor
	ArgumentCaptor<EntityChildrenRequest> childRequestCaptor;
	
	@Captor
	ArgumentCaptor<List<FileHandleAssociation>> associationCaptor;
	
	@Captor
	ArgumentCaptor<List<String>> idsCaptor;
	
	@Captor
	ArgumentCaptor<QueryBundleRequest> queryBundleCaptor;
	
	@Captor
	ArgumentCaptor<BatchFileRequest> batchFileCaptor;
	
	@Captor
	ArgumentCaptor<DownloadOrder> downloadOrderCaptor;

	UserInfo userInfo;
	String folderId;
	List<EntityHeader> headers;
	String nextPageToken;
	EntityChildrenResponse pageOne;
	EntityChildrenResponse pageTwo;

	String tableId;
	IdAndVersion tableIdAndVersion;
	RowSet rowset;
	QueryResultBundle queryResult;
	Query query;

	List<FileHandleAssociation> fullList;
	String zipFileName;
	
	DownloadOrder downloadOrder;
	DownloadList addedFiles;
	List<FileHandleAssociation> associations;
	BatchFileResult batchFileResult;
	S3FileHandle s3FileHandle;
	FileResult authorizedS3;
	DownloadList downloadList;

	@BeforeEach
	public void before() throws Exception {
		userInfo = new UserInfo(false, 123L);
		folderId = "syn123";
		headers = new LinkedList<>();
		for (int i = 0; i < 5; i++) {
			EntityHeader header = new EntityHeader();
			header.setId("" + i);
			header.setName("name" + i);
			headers.add(header);
			header.setVersionNumber(i * 3L);
		}
		nextPageToken = "hasNextPageToken";
		pageOne = new EntityChildrenResponse();
		pageOne.setNextPageToken(nextPageToken);
		pageOne.setPage(headers.subList(0, 2));

		pageTwo = new EntityChildrenResponse();
		pageTwo.setNextPageToken(null);
		pageTwo.setPage(headers.subList(2, 4));

		pageTwo = new EntityChildrenResponse();
		pageTwo.setNextPageToken(null);
		pageTwo.setPage(headers.subList(2, 4));

		addedFiles = new DownloadList();
		addedFiles.setFilesToDownload(new LinkedList<>());
		
		associations = createResultsOfSize(4);

		tableId = "syn123";
		tableIdAndVersion = IdAndVersion.parse(tableId);
		rowset = new RowSet();
		rowset.setRows(createRows(2));
		rowset.setTableId(tableId);
		QueryResult qr = new QueryResult();
		qr.setQueryResults(rowset);
		queryResult = new QueryResultBundle();
		queryResult.setQueryResult(qr);

		query = new Query();
		query.setSql("select * from " + tableId);
	
		fullList = createResultsOfSize(1);
		downloadList = new DownloadList();
		downloadList.setFilesToDownload(fullList);
		
		// authorized S3FileHandle
		s3FileHandle = new S3FileHandle();
		s3FileHandle.setContentSize(100L);
		s3FileHandle.setId(fullList.get(0).getFileHandleId());
		authorizedS3 = new FileResult();
		authorizedS3.setFailureCode(null);
		authorizedS3.setFileHandle(s3FileHandle);

		batchFileResult = new BatchFileResult();
		batchFileResult.setRequestedFiles(Lists.newArrayList(authorizedS3));

		zipFileName = "theZip.zip";
		
		downloadOrder = new DownloadOrder();
		downloadOrder.setCreatedBy(userInfo.getId().toString());
		downloadOrder.setOrderId("123");
	}

	@Test
	public void testAddFilesFromFolder() {
		when(mockEntityManager.getChildren(any(UserInfo.class), any(EntityChildrenRequest.class))).thenReturn(pageOne,	pageTwo);
		when(mockBulkDownloadDao.addFilesToDownloadList(any(String.class), anyListOf(FileHandleAssociation.class))).thenReturn(addedFiles);
		when(mockBulkDownloadDao.getUsersDownloadList(any(String.class))).thenReturn(addedFiles);
		when(mockNodeDao.getFileHandleAssociationsForCurrentVersion(anyListOf(String.class))).thenReturn(associations.subList(0, 2), associations.subList(2, 4));
		
		// call under test
		DownloadList list = manager.addFilesFromFolder(userInfo, folderId);
		assertNotNull(list);
		// two pages
		verify(mockEntityManager, times(2)).getChildren(any(UserInfo.class), childRequestCaptor.capture());
		verify(mockNodeDao, times(2)).getFileHandleAssociationsForCurrentVersion(idsCaptor.capture());
		verify(mockBulkDownloadDao, times(2)).addFilesToDownloadList(any(String.class), associationCaptor.capture());

		List<EntityChildrenRequest> childRequests = childRequestCaptor.getAllValues();
		assertNotNull(childRequests);
		assertEquals(2, childRequests.size());
		// first request
		EntityChildrenRequest request = childRequests.get(0);
		assertEquals(folderId, request.getParentId());
		assertEquals(Lists.newArrayList(EntityType.file), request.getIncludeTypes());
		// first request should not have a token
		assertEquals(null, request.getNextPageToken());
		// second request
		request = childRequests.get(1);
		// the second request should include a token
		assertEquals(nextPageToken, request.getNextPageToken());

		List<List<FileHandleAssociation>> capturedAssociations = associationCaptor.getAllValues();
		assertNotNull(capturedAssociations);
		// to calls should be captured.
		assertEquals(2, capturedAssociations.size());
		// first call
		List<FileHandleAssociation> added = capturedAssociations.get(0);
		assertNotNull(added);
		assertEquals(2, added.size());
		FileHandleAssociation association = added.get(0);
		assertEquals("0", association.getAssociateObjectId());
		assertEquals(FileHandleAssociateType.FileEntity, association.getAssociateObjectType());
		assertEquals("000", association.getFileHandleId());

		// second call
		added = capturedAssociations.get(1);
		assertNotNull(added);
		assertEquals(2, added.size());
		association = added.get(1);
		assertEquals("3", association.getAssociateObjectId());
		assertEquals(FileHandleAssociateType.FileEntity, association.getAssociateObjectType());
		assertEquals("333", association.getFileHandleId());

		List<List<String>> capturedId = idsCaptor.getAllValues();
		assertNotNull(capturedId);
		assertEquals(2, capturedId.size());
		// first call
		assertEquals(Lists.newArrayList("0", "1"), capturedId.get(0));
		// second call
		assertEquals(Lists.newArrayList("2", "3"), capturedId.get(1));

	}

	@Test
	public void testAddFilesFromFolderNoChildren() {
		when(mockBulkDownloadDao.addFilesToDownloadList(any(String.class), anyListOf(FileHandleAssociation.class))).thenReturn(addedFiles);
		when(mockBulkDownloadDao.getUsersDownloadList(any(String.class))).thenReturn(addedFiles);
		when(mockNodeDao.getFileHandleAssociationsForCurrentVersion(anyListOf(String.class))).thenReturn(associations.subList(0, 2), associations.subList(2, 4));
		
		// setup no children.
		EntityChildrenResponse noResutls = new EntityChildrenResponse();
		noResutls.setNextPageToken(null);
		noResutls.setPage(new LinkedList<>());
		when(mockEntityManager.getChildren(any(UserInfo.class), any(EntityChildrenRequest.class)))
				.thenReturn(noResutls);
		when(mockNodeDao.getFileHandleAssociationsForCurrentVersion(anyListOf(String.class)))
				.thenReturn(new LinkedList<>());
		// call under test
		DownloadList list = manager.addFilesFromFolder(userInfo, folderId);
		assertNotNull(list);
		verify(mockEntityManager).getChildren(any(UserInfo.class), any(EntityChildrenRequest.class));
		verify(mockNodeDao).getFileHandleAssociationsForCurrentVersion(anyListOf(String.class));
		verify(mockBulkDownloadDao).addFilesToDownloadList(any(String.class), anyListOf(FileHandleAssociation.class));
	}

	@Test
	public void testAddFilesFromFolderOverLimit() {
		when(mockEntityManager.getChildren(any(UserInfo.class), any(EntityChildrenRequest.class))).thenReturn(pageOne,	pageTwo);
		when(mockNodeDao.getFileHandleAssociationsForCurrentVersion(anyListOf(String.class))).thenReturn(associations.subList(0, 2), associations.subList(2, 4));
		
		// setup over limit
		DownloadList usersList = new DownloadList();
		usersList.setFilesToDownload(createResultsOfSize(BulkDownloadManagerImpl.MAX_FILES_PER_DOWNLOAD_LIST + 1));
		when(mockBulkDownloadDao.addFilesToDownloadList(any(String.class), anyListOf(FileHandleAssociation.class)))
				.thenReturn(usersList);
		try {
			// call under test
			manager.addFilesFromFolder(userInfo, folderId);
			fail();
		} catch (IllegalArgumentException e) {
			assertEquals(BulkDownloadManagerImpl.EXCEEDED_MAX_NUMBER_ROWS, e.getMessage());
		}
	}

	@Test
	public void testAddFilesFromFolderNullUser() {
		userInfo = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.addFilesFromFolder(userInfo, folderId);
		});
	}

	@Test
	public void testAddFilesFromFolderNullFolder() {
		folderId = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.addFilesFromFolder(userInfo, folderId);
		});
	}

	@Test
	public void testAttemptToAddFilesToUsersDownloadList() {
		when(mockBulkDownloadDao.addFilesToDownloadList(any(String.class), anyListOf(FileHandleAssociation.class))).thenReturn(addedFiles);
		List<FileHandleAssociation> toAdd = createResultsOfSize(2);
		// call under test
		manager.attemptToAddFilesToUsersDownloadList(userInfo, toAdd);
		verify(mockBulkDownloadDao).addFilesToDownloadList("" + userInfo.getId(), toAdd);
	}

	@Test
	public void testAttemptToAddFilesToUsersDownloadListEmpty() {
		when(mockBulkDownloadDao.addFilesToDownloadList(any(String.class), anyListOf(FileHandleAssociation.class))).thenReturn(addedFiles);
		List<FileHandleAssociation> toAdd = new LinkedList<>();
		// call under test
		manager.attemptToAddFilesToUsersDownloadList(userInfo, toAdd);
		verify(mockBulkDownloadDao).addFilesToDownloadList(any(String.class), anyListOf(FileHandleAssociation.class));
	}

	@Test
	public void testAddFilesFromQuery() throws Exception {
		when(mockBulkDownloadDao.addFilesToDownloadList(any(String.class), anyListOf(FileHandleAssociation.class))).thenReturn(addedFiles);
		when(mockNodeDao.getFileHandleIdForVersion("0", 24L)).thenReturn("89");
		when(mockNodeDao.getFileHandleIdForVersion("1", 25L)).thenReturn("90");
		when(mockTableQueryManager.queryBundle(any(ProgressCallback.class), any(UserInfo.class),any(QueryBundleRequest.class))).thenReturn(queryResult);
		when(mockEntityManager.getEntityType(userInfo, tableIdAndVersion.getId().toString())).thenReturn(EntityType.entityview);
		
		// call under test
		DownloadList result = manager.addFilesFromQuery(mockProgressCallback, userInfo, query);
		assertNotNull(result);
		verify(mockTableQueryManager).queryBundle(any(ProgressCallback.class), any(UserInfo.class),
				queryBundleCaptor.capture());
		verify(mockNodeDao, times(2)).getFileHandleIdForVersion(any(), any());
		verify(mockNodeDao).getFileHandleIdForVersion("0", 24L);
		verify(mockNodeDao).getFileHandleIdForVersion("1", 25L);
		verify(mockBulkDownloadDao).addFilesToDownloadList(any(String.class), associationCaptor.capture());

		// validate query request
		QueryBundleRequest queryRequest = queryBundleCaptor.getValue();
		assertNotNull(queryRequest);
		assertEquals(new Long(BulkDownloadManagerImpl.QUERY_ONLY_PART_MASK), queryRequest.getPartMask());
		assertNotNull(queryRequest.getQuery());
		assertEquals(new Long(BulkDownloadManagerImpl.MAX_FILES_PER_DOWNLOAD_LIST + 1),
				queryRequest.getQuery().getLimit());
		assertEquals(query, queryRequest.getQuery());

		// validate add
		List<FileHandleAssociation> associations = associationCaptor.getValue();
		assertNotNull(associations);
		assertEquals(2, associations.size());
		FileHandleAssociation association = associations.get(0);
		assertEquals("0", association.getAssociateObjectId());
		assertEquals(FileHandleAssociateType.FileEntity, association.getAssociateObjectType());
		assertEquals("89", association.getFileHandleId());
		
		association = associations.get(1);
		assertEquals("1", association.getAssociateObjectId());
		assertEquals(FileHandleAssociateType.FileEntity, association.getAssociateObjectType());
		assertEquals("90", association.getFileHandleId());
	}
	
	@Test
	public void testAddFilesFromQueryWithSnapshot() throws Exception {
		// dot version number indicates a query from a snapshot.
		tableIdAndVersion = IdAndVersion.parse(tableId+".12");
		query.setSql("select * from "+tableIdAndVersion.toString());
		when(mockBulkDownloadDao.addFilesToDownloadList(any(String.class), any())).thenReturn(addedFiles);
		when(mockNodeDao.getFileHandleIdForVersion("0", 24L)).thenReturn("89");
		when(mockNodeDao.getFileHandleIdForVersion("1", 25L)).thenReturn("90");
		when(mockTableQueryManager.queryBundle(any(ProgressCallback.class), any(UserInfo.class),any(QueryBundleRequest.class))).thenReturn(queryResult);
		when(mockEntityManager.getEntityType(userInfo, tableIdAndVersion.getId().toString())).thenReturn(EntityType.entityview);
		
		// call under test
		DownloadList result = manager.addFilesFromQuery(mockProgressCallback, userInfo, query);
		assertNotNull(result);
		verify(mockTableQueryManager).queryBundle(any(ProgressCallback.class), any(UserInfo.class),
				queryBundleCaptor.capture());
		verify(mockNodeDao, times(2)).getFileHandleIdForVersion(any(), any());
		verify(mockNodeDao).getFileHandleIdForVersion("0", 24L);
		verify(mockNodeDao).getFileHandleIdForVersion("1", 25L);
		verify(mockBulkDownloadDao).addFilesToDownloadList(any(String.class), associationCaptor.capture());

		// validate query request
		QueryBundleRequest queryRequest = queryBundleCaptor.getValue();
		assertNotNull(queryRequest);
		assertEquals(new Long(BulkDownloadManagerImpl.QUERY_ONLY_PART_MASK), queryRequest.getPartMask());
		assertNotNull(queryRequest.getQuery());
		assertEquals(new Long(BulkDownloadManagerImpl.MAX_FILES_PER_DOWNLOAD_LIST + 1),
				queryRequest.getQuery().getLimit());
		assertEquals(query, queryRequest.getQuery());

		// validate add
		List<FileHandleAssociation> associations = associationCaptor.getValue();
		assertNotNull(associations);
		assertEquals(2, associations.size());
		FileHandleAssociation association = associations.get(0);
		assertEquals("0", association.getAssociateObjectId());
		assertEquals(FileHandleAssociateType.FileEntity, association.getAssociateObjectType());
		assertEquals("89", association.getFileHandleId());
		
		association = associations.get(1);
		assertEquals("1", association.getAssociateObjectId());
		assertEquals(FileHandleAssociateType.FileEntity, association.getAssociateObjectType());
		assertEquals("90", association.getFileHandleId());
	}

	@Test
	public void testAddFilesFromQueryNotAview() throws Exception {
		when(mockTableQueryManager.queryBundle(any(ProgressCallback.class), any(UserInfo.class),any(QueryBundleRequest.class))).thenReturn(queryResult);
		// case where the table is not a view
		when(mockEntityManager.getEntityType(userInfo, tableIdAndVersion.getId().toString())).thenReturn(EntityType.table);
		try {
			// call under test
			manager.addFilesFromQuery(mockProgressCallback, userInfo, query);
			fail();
		} catch (IllegalArgumentException e) {
			assertEquals(BulkDownloadManagerImpl.FILES_CAN_ONLY_BE_ADDED_FROM_A_FILE_VIEW_QUERY, e.getMessage());
		}
		verify(mockTableQueryManager).queryBundle(any(ProgressCallback.class), any(UserInfo.class),
				any(QueryBundleRequest.class));
		verify(mockNodeDao, never()).getFileHandleAssociationsForCurrentVersion(anyListOf(String.class));
		verify(mockBulkDownloadDao, never()).addFilesToDownloadList(any(String.class),
				anyListOf(FileHandleAssociation.class));
	}

	@Test
	public void testAddFilesFromQueryTooManyRows() throws Exception {
		when(mockTableQueryManager.queryBundle(any(ProgressCallback.class), any(UserInfo.class),any(QueryBundleRequest.class))).thenReturn(queryResult);
		when(mockEntityManager.getEntityType(userInfo, tableIdAndVersion.getId().toString())).thenReturn(EntityType.entityview);
		
		// setup query result with more than the max number of rows.
		rowset.setRows(createRows(BulkDownloadManagerImpl.MAX_FILES_PER_DOWNLOAD_LIST + 1));
		try {
			// call under test
			manager.addFilesFromQuery(mockProgressCallback, userInfo, query);
			fail();
		} catch (IllegalArgumentException e) {
			assertEquals(BulkDownloadManagerImpl.EXCEEDED_MAX_NUMBER_ROWS, e.getMessage());
		}
	}

	@Test
	public void testAddFilesFromQueryLockUnavilableException() throws Exception {
		LockUnavilableException exception = new LockUnavilableException(LockType.Read, "key", "context");
		when(mockTableQueryManager.queryBundle(any(ProgressCallback.class), any(UserInfo.class),
				any(QueryBundleRequest.class))).thenThrow(exception);
		assertThrows(RecoverableMessageException.class, ()->{
			// call under test
			manager.addFilesFromQuery(mockProgressCallback, userInfo, query);
		});
	}

	@Test
	public void testAddFilesFromQueryTableUnavailableException() throws Exception {
		TableUnavailableException exception = new TableUnavailableException(null);
		when(mockTableQueryManager.queryBundle(any(ProgressCallback.class), any(UserInfo.class),
				any(QueryBundleRequest.class))).thenThrow(exception);

		assertThrows(RecoverableMessageException.class, ()->{
			// call under test
			manager.addFilesFromQuery(mockProgressCallback, userInfo, query);
		});
	}

	@Test
	public void testAddFilesFromQueryParseException() throws Exception {
		ParseException exception = new ParseException();
		when(mockTableQueryManager.queryBundle(any(ProgressCallback.class), any(UserInfo.class),
				any(QueryBundleRequest.class))).thenThrow(exception);

		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.addFilesFromQuery(mockProgressCallback, userInfo, query);
		});
	}

	@Test
	public void testAddFilesFromQueryNullUser() throws Exception {
		userInfo = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.addFilesFromQuery(mockProgressCallback, userInfo, query);
		});
	}

	@Test
	public void testAddFilesFromQueryNullQuery() throws Exception {
		query = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.addFilesFromQuery(mockProgressCallback, userInfo, query);
		});
	}

	@Test
	public void testAddFileHandleAssociations() {
		when(mockBulkDownloadDao.addFilesToDownloadList(any(String.class), anyListOf(FileHandleAssociation.class))).thenReturn(addedFiles);
		List<FileHandleAssociation> toAdd = createResultsOfSize(10);
		// call under test
		manager.addFileHandleAssociations(userInfo, toAdd);
		verify(mockBulkDownloadDao).addFilesToDownloadList(userInfo.getId().toString(), toAdd);
	}

	@Test
	public void testAddFileHandleAssociationsEmpty() {
		when(mockBulkDownloadDao.addFilesToDownloadList(any(String.class), anyListOf(FileHandleAssociation.class))).thenReturn(addedFiles);
		List<FileHandleAssociation> toAdd = new LinkedList<>();
		// call under test
		manager.addFileHandleAssociations(userInfo, toAdd);
		verify(mockBulkDownloadDao).addFilesToDownloadList(userInfo.getId().toString(), toAdd);
	}

	@Test
	public void testAddFileHandleAssociationsNullUser() {
		List<FileHandleAssociation> toAdd = createResultsOfSize(10);
		userInfo = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.addFileHandleAssociations(userInfo, toAdd);
		});
	}

	@Test
	public void testAddFileHandleAssociationsNullList() {
		List<FileHandleAssociation> toAdd = null;

		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.addFileHandleAssociations(userInfo, toAdd);
		});
	}

	@Test
	public void testAddFileHandleAssociationsOverLimit() {
		List<FileHandleAssociation> toAdd = createResultsOfSize(
				BulkDownloadManagerImpl.MAX_FILES_PER_DOWNLOAD_LIST + 1);
		DownloadList results = new DownloadList();
		results.setFilesToDownload(toAdd);
		when(mockBulkDownloadDao.addFilesToDownloadList(userInfo.getId().toString(), toAdd)).thenReturn(results);
		try {
			// call under test
			manager.addFileHandleAssociations(userInfo, toAdd);
			fail();
		} catch (IllegalArgumentException e) {
			assertEquals(BulkDownloadManagerImpl.EXCEEDED_MAX_NUMBER_ROWS, e.getMessage());
		}
	}

	@Test
	public void testRemoveFileHandleAssociations() {
		List<FileHandleAssociation> toRemove = createResultsOfSize(2);
		// call under test
		manager.removeFileHandleAssociations(userInfo, toRemove);
		verify(mockBulkDownloadDao).removeFilesFromDownloadList(userInfo.getId().toString(), toRemove);
	}

	@Test
	public void testRemoveFileHandleAssociationsEmpty() {
		List<FileHandleAssociation> toRemove = new LinkedList<>();
		// call under test
		manager.removeFileHandleAssociations(userInfo, toRemove);
		verify(mockBulkDownloadDao).removeFilesFromDownloadList(userInfo.getId().toString(), toRemove);
	}

	@Test
	public void testRemoveFileHandleAssociationsNullUser() {
		List<FileHandleAssociation> toRemove = createResultsOfSize(2);
		userInfo = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.removeFileHandleAssociations(userInfo, toRemove);
		});
	}

	@Test
	public void testRemoveFileHandleAssociationsNullList() {
		List<FileHandleAssociation> toRemove = null;

		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.removeFileHandleAssociations(userInfo, toRemove);
		});
	}

	@Test
	public void testGetDownloadList() {
		when(mockBulkDownloadDao.getUsersDownloadList(any(String.class))).thenReturn(addedFiles);
		// call under test
		DownloadList list = manager.getDownloadList(userInfo);
		assertNotNull(list);
		verify(mockBulkDownloadDao).getUsersDownloadList(userInfo.getId().toString());
	}

	@Test
	public void testGetDownloadListNullUser() {
		userInfo = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.getDownloadList(userInfo);
		});
	}

	@Test
	public void testClearDownloadList() {
		when(mockBulkDownloadDao.clearDownloadList(any(String.class))).thenReturn(addedFiles);
		// call under test
		DownloadList list = manager.clearDownloadList(userInfo);
		assertNotNull(list);
		verify(mockBulkDownloadDao).clearDownloadList(userInfo.getId().toString());
	}

	@Test
	public void testClearDownloadListNullUser() {
		userInfo = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.clearDownloadList(userInfo);
		});
	}

	@Test
	public void truncateAllDownloadDataForAllUsersAdmin() {
		boolean isAdmin = true;
		// call under test
		manager.truncateAllDownloadDataForAllUsers(new UserInfo(isAdmin));
		verify(mockBulkDownloadDao).truncateAllDownloadDataForAllUsers();
	}

	@Test
	public void truncateAllDownloadDataForAllUsersNonAdmin() {
		boolean isAdmin = false;
		try {
			// call under test
			manager.truncateAllDownloadDataForAllUsers(new UserInfo(isAdmin));
			fail();
		} catch (UnauthorizedException e) {
			// expected
		}
		verify(mockBulkDownloadDao, never()).truncateAllDownloadDataForAllUsers();
	}

	@Test
	public void testGetSizesOfDownloadableFilesAuthorizedS3File() {
		when(mockFileHandlerManager.getFileHandleAndUrlBatch(any(UserInfo.class), any(BatchFileRequest.class))).thenReturn(batchFileResult);
		
		List<FileHandleAssociation> files = createResultsOfSize(5);
		// call under test
		Map<String, Long> downloadableSize = manager.getSizesOfDownloadableFiles(userInfo, files);
		assertNotNull(downloadableSize);
		assertEquals(1, downloadableSize.size());
		assertEquals(new Long(100), downloadableSize.get("000"));
		verify(mockFileHandlerManager).getFileHandleAndUrlBatch(any(UserInfo.class), batchFileCaptor.capture());
		BatchFileRequest batchRequest = batchFileCaptor.getValue();
		assertNotNull(batchRequest);
		assertEquals(Boolean.TRUE, batchRequest.getIncludeFileHandles());
		assertEquals(Boolean.FALSE, batchRequest.getIncludePreSignedURLs());
		assertEquals(Boolean.FALSE, batchRequest.getIncludePreviewPreSignedURLs());
		assertEquals(files, batchRequest.getRequestedFiles());
	}

	@Test
	public void testGetSizesOfDownloadableFilesUnauthorizedS3File() {
		// unauthorized S3FileHandle
		S3FileHandle s3FileHandle = new S3FileHandle();
		s3FileHandle.setContentSize(1000L);
		s3FileHandle.setId("432");
		FileResult unauthorizedS3 = new FileResult();
		unauthorizedS3.setFailureCode(FileResultFailureCode.UNAUTHORIZED);
		unauthorizedS3.setFileHandle(s3FileHandle);

		BatchFileResult batchFileResult = new BatchFileResult();
		batchFileResult.setRequestedFiles(Lists.newArrayList(unauthorizedS3));
		when(mockFileHandlerManager.getFileHandleAndUrlBatch(any(UserInfo.class), any(BatchFileRequest.class)))
				.thenReturn(batchFileResult);
		List<FileHandleAssociation> files = createResultsOfSize(1);
		// call under test
		Map<String, Long> downloadableSize = manager.getSizesOfDownloadableFiles(userInfo, files);
		assertNotNull(downloadableSize);
		assertEquals(0, downloadableSize.size());
	}

	@Test
	public void testGetSizesOfDownloadableFilesAuthorizedExternal() {
		// authorized ExternalFileHandle
		ExternalFileHandle external = new ExternalFileHandle();
		external.setId("543");
		FileResult authorizedExternal = new FileResult();
		authorizedExternal.setFailureCode(null);
		authorizedExternal.setFileHandle(external);

		BatchFileResult batchFileResult = new BatchFileResult();
		batchFileResult.setRequestedFiles(Lists.newArrayList(authorizedExternal));
		when(mockFileHandlerManager.getFileHandleAndUrlBatch(any(UserInfo.class), any(BatchFileRequest.class)))
				.thenReturn(batchFileResult);
		List<FileHandleAssociation> files = createResultsOfSize(1);
		// call under test
		Map<String, Long> downloadableSize = manager.getSizesOfDownloadableFiles(userInfo, files);
		assertNotNull(downloadableSize);
		assertEquals(0, downloadableSize.size());
	}

	@Test
	public void testGetSizesOfDownloadableFilesUnauthorizedExternal() {
		// authorized ExternalFileHandle
		ExternalFileHandle external = new ExternalFileHandle();
		external.setId("543");
		FileResult authorizedExternal = new FileResult();
		authorizedExternal.setFailureCode(FileResultFailureCode.UNAUTHORIZED);
		authorizedExternal.setFileHandle(external);

		BatchFileResult batchFileResult = new BatchFileResult();
		batchFileResult.setRequestedFiles(Lists.newArrayList(authorizedExternal));
		when(mockFileHandlerManager.getFileHandleAndUrlBatch(any(UserInfo.class), any(BatchFileRequest.class)))
				.thenReturn(batchFileResult);
		List<FileHandleAssociation> files = createResultsOfSize(1);
		// call under test
		Map<String, Long> downloadableSize = manager.getSizesOfDownloadableFiles(userInfo, files);
		assertNotNull(downloadableSize);
		assertEquals(0, downloadableSize.size());
	}

	@Test
	public void testGetSizesOfDownloadableFilesNotFound() {
		// authorized ExternalFileHandle
		ExternalFileHandle external = new ExternalFileHandle();
		external.setId("543");
		FileResult notFound = new FileResult();
		notFound.setFailureCode(FileResultFailureCode.NOT_FOUND);
		notFound.setFileHandleId("456");

		BatchFileResult batchFileResult = new BatchFileResult();
		batchFileResult.setRequestedFiles(Lists.newArrayList(notFound));
		when(mockFileHandlerManager.getFileHandleAndUrlBatch(any(UserInfo.class), any(BatchFileRequest.class)))
				.thenReturn(batchFileResult);
		List<FileHandleAssociation> files = createResultsOfSize(1);
		// call under test
		Map<String, Long> downloadableSize = manager.getSizesOfDownloadableFiles(userInfo, files);
		assertNotNull(downloadableSize);
		assertEquals(0, downloadableSize.size());
	}

	@Test
	public void testBuildDownloadOrderUnderSizeLimit() {
		List<FileHandleAssociation> fullList = createResultsOfSize(100);
		Map<String, Long> downloadableFileSizes = new HashMap<>();
		for (FileHandleAssociation association : fullList) {
			downloadableFileSizes.put(association.getFileHandleId(),
					FileConstants.BULK_FILE_DOWNLOAD_MAX_SIZE_BYTES / 10);
		}
		// call under test
		DownloadOrder order = BulkDownloadManagerImpl.buildDownloadOrderUnderSizeLimit(userInfo, fullList,
				downloadableFileSizes, zipFileName);
		assertNotNull(order);
		assertEquals(zipFileName, order.getZipFileName());
		assertEquals(userInfo.getId().toString(), order.getCreatedBy());
		assertNotNull(order.getCreatedOn());
		assertEquals(new Long(10), order.getTotalNumberOfFiles());
		assertTrue(order.getTotalSizeBytes() < FileConstants.BULK_FILE_DOWNLOAD_MAX_SIZE_BYTES);
		assertNotNull(order.getFiles());
		assertEquals(10, order.getFiles().size());
		assertEquals(fullList.subList(0, 10), order.getFiles());
	}

	@Test
	public void testBuildDownloadOrderUnderSizeLimitEmptyMap() {
		List<FileHandleAssociation> fullList = createResultsOfSize(100);
		// empty map should filter all files.
		Map<String, Long> downloadableFileSizes = new HashMap<>();
		// call under test
		DownloadOrder order = BulkDownloadManagerImpl.buildDownloadOrderUnderSizeLimit(userInfo, fullList,
				downloadableFileSizes, zipFileName);
		assertNotNull(order);
		assertEquals(0, order.getFiles().size());
	}

	@Test
	public void testBuildDownloadOrderUnderSizeLimitAtLimit() {
		List<FileHandleAssociation> fullList = createResultsOfSize(1);
		Map<String, Long> downloadableFileSizes = new HashMap<>();
		downloadableFileSizes.put(fullList.get(0).getFileHandleId(), FileConstants.BULK_FILE_DOWNLOAD_MAX_SIZE_BYTES);
		// call under test
		DownloadOrder order = BulkDownloadManagerImpl.buildDownloadOrderUnderSizeLimit(userInfo, fullList,
				downloadableFileSizes, zipFileName);
		assertNotNull(order);
		assertEquals(1, order.getFiles().size());
		assertEquals(fullList, order.getFiles());
	}

	@Test
	public void testBuildDownloadOrderUnderSizeLimitOverLimit() {
		List<FileHandleAssociation> fullList = createResultsOfSize(1);
		Map<String, Long> downloadableFileSizes = new HashMap<>();
		downloadableFileSizes.put(fullList.get(0).getFileHandleId(),
				FileConstants.BULK_FILE_DOWNLOAD_MAX_SIZE_BYTES + 1);
		// call under test
		DownloadOrder order = BulkDownloadManagerImpl.buildDownloadOrderUnderSizeLimit(userInfo, fullList,
				downloadableFileSizes, zipFileName);
		assertNotNull(order);
		assertEquals(0, order.getFiles().size());
	}

	@Test
	public void testCreateDownloadOrder() {
		when(mockBulkDownloadDao.getUsersDownloadListForUpdate(any(String.class))).thenReturn(downloadList);
		when(mockFileHandlerManager.getFileHandleAndUrlBatch(any(UserInfo.class), any(BatchFileRequest.class))).thenReturn(batchFileResult);
		when(mockBulkDownloadDao.createDownloadOrder(any(DownloadOrder.class))).thenReturn(new DownloadOrder());
		
		// call under test
		DownloadOrder order = manager.createDownloadOrder(userInfo, zipFileName);
		assertNotNull(order);
		// for update version must be used.
		verify(mockBulkDownloadDao).getUsersDownloadListForUpdate(userInfo.getId().toString());
		verify(mockBulkDownloadDao).removeFilesFromDownloadList(userInfo.getId().toString(), fullList);
		verify(mockBulkDownloadDao).createDownloadOrder(downloadOrderCaptor.capture());
		DownloadOrder orderCaptured = downloadOrderCaptor.getValue();
		assertNotNull(orderCaptured);
		assertEquals(userInfo.getId().toString(), orderCaptured.getCreatedBy());
		assertNotNull(orderCaptured.getCreatedOn());
		assertEquals(zipFileName, orderCaptured.getZipFileName());
		assertEquals(fullList, orderCaptured.getFiles());
	}
	
	@Test
	public void testCreateDownloadOrderEmptyList() {
		DownloadList emptyOrder = new DownloadList();
		emptyOrder.setFilesToDownload(new LinkedList<>());
		when(mockBulkDownloadDao.getUsersDownloadListForUpdate(any(String.class))).thenReturn(emptyOrder);
		
		try {
			// call under test
			manager.createDownloadOrder(userInfo, zipFileName);
			fail();
		} catch (IllegalArgumentException e) {
			assertEquals(BulkDownloadManagerImpl.THE_DOWNLOAD_LIST_IS_EMPTY, e.getMessage());
		}
		verify(mockBulkDownloadDao, never()).removeFilesFromDownloadList(any(String.class), anyListOf(FileHandleAssociation.class));
		verify(mockBulkDownloadDao, never()).createDownloadOrder(any(DownloadOrder.class));
	}
	
	@Test
	public void testCreateDownloadOrderNoFilesCanBeDownload() {
		when(mockBulkDownloadDao.getUsersDownloadListForUpdate(any(String.class))).thenReturn(downloadList);
		
		BatchFileResult batchResult = new BatchFileResult();
		batchResult.setRequestedFiles(new LinkedList<>());
		when(mockFileHandlerManager.getFileHandleAndUrlBatch(any(UserInfo.class), any(BatchFileRequest.class))).thenReturn(batchResult);
		try {
			// call under test
			manager.createDownloadOrder(userInfo, zipFileName);
			fail();
		} catch (IllegalArgumentException e) {
			assertEquals(BulkDownloadManagerImpl.COULD_NOT_DOWNLOAD_ANY_FILES_FROM_THE_DOWNLOAD_LIST, e.getMessage());
		}
		verify(mockBulkDownloadDao, never()).removeFilesFromDownloadList(any(String.class), anyListOf(FileHandleAssociation.class));
		verify(mockBulkDownloadDao, never()).createDownloadOrder(any(DownloadOrder.class));
	}
	
	@Test
	public void testCreateDownloadOrderNullUser() {
		userInfo = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.createDownloadOrder(userInfo, zipFileName);
		});
	}
	
	@Test
	public void testCreateDownloadOrderNullFileName() {
		zipFileName = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.createDownloadOrder(userInfo, zipFileName);
		});
	}
	
	@Test
	public void testGetDownloadHistory() {
		int pageSize = (int)NextPageToken.DEFAULT_LIMIT+1;
		List<DownloadOrderSummary> page = buildSummary(pageSize);
		when(mockBulkDownloadDao.getUsersDownloadOrders(any(String.class), any(Long.class), any(Long.class))).thenReturn(page);
		DownloadOrderSummaryRequest request = new DownloadOrderSummaryRequest();
		request.setNextPageToken(null);
		// call under test
		DownloadOrderSummaryResponse response = manager.getDownloadHistory(userInfo, request);
		assertNotNull(response);
		assertNotNull(response.getNextPageToken());
		assertNotNull(response.getPage());
		// last row is removed to single a next page token.
		assertEquals(pageSize-1, response.getPage().size());
	}
	
	@Test
	public void testGetDownloadHistoryNullUser() {
		DownloadOrderSummaryRequest request = new DownloadOrderSummaryRequest();
		userInfo = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.getDownloadHistory(userInfo, request);
		});
	}
	
	@Test
	public void testGetDownloadHistoryNullRequest() {
		DownloadOrderSummaryRequest request = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.getDownloadHistory(userInfo, request);
		});
	}
	
	@Test
	public void testGetDownloadOrder() {
		when(mockBulkDownloadDao.getDownloadOrder(any(String.class))).thenReturn(downloadOrder);
		String orderId = "123";
		// call under test
		DownloadOrder result = manager.getDownloadOrder(userInfo, orderId);
		assertEquals(downloadOrder, result);
	}
	
	@Test
	public void testGetDownloadOrderUnauthorized() {
		when(mockBulkDownloadDao.getDownloadOrder(any(String.class))).thenReturn(downloadOrder);
		// order created by another.
		downloadOrder.setCreatedBy(userInfo.getId().toString()+"1");
		String orderId = "123";
		assertThrows(UnauthorizedException.class, ()->{
			// call under test
			manager.getDownloadOrder(userInfo, orderId);
		});
	}
	
	@Test
	public void testGetDownloadOrderNullOrderId() {
		String orderId = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.getDownloadOrder(userInfo, orderId);
		});
	}
	
	@Test
	public void testGetDownloadOrderNullUser() {
		String orderId = "123";
		userInfo = null;
		assertThrows(IllegalArgumentException.class, ()->{
			// call under test
			manager.getDownloadOrder(userInfo, orderId);
		});
	}
	
	/**
	 * Helper to build List<DownloadOrderSummary> of a given size.
	 * 
	 * @param size
	 * @return
	 */
	static List<DownloadOrderSummary> buildSummary(int size){
		List<DownloadOrderSummary> result = new LinkedList<>();
		for(int i=0; i<size; i++) {
			DownloadOrderSummary summary = new DownloadOrderSummary();
			summary.setOrderId(""+i);
			result.add(summary);
		}
		return result;
	}

	/**
	 * Test helper.
	 * 
	 * @param size
	 * @return
	 */
	static List<FileHandleAssociation> createResultsOfSize(int size) {
		List<FileHandleAssociation> result = new LinkedList<>();
		for (int i = 0; i < size; i++) {
			FileHandleAssociation fha = new FileHandleAssociation();
			fha.setAssociateObjectId("" + i);
			fha.setAssociateObjectType(FileHandleAssociateType.FileEntity);
			String indexString = "" + i;
			fha.setFileHandleId(indexString + indexString + indexString);
			result.add(fha);
		}
		return result;
	}

	/**
	 * Test helper.
	 * 
	 * @return
	 */
	static List<Row> createRows(int size) {
		List<Row> results = new LinkedList<>();
		for (int i = 0; i < size; i++) {
			Row row = new Row();
			row.setRowId(new Long(i));
			row.setVersionNumber(24L+i);
			results.add(row);
		}
		return results;
	}
}
