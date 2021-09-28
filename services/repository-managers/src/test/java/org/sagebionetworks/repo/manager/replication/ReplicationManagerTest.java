package org.sagebionetworks.repo.manager.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.LoggerProvider;
import org.sagebionetworks.repo.manager.table.TableIndexConnectionFactory;
import org.sagebionetworks.repo.manager.table.TableIndexManager;
import org.sagebionetworks.repo.manager.table.TableManagerSupport;
import org.sagebionetworks.repo.manager.table.metadata.MetadataIndexProviderFactory;
import org.sagebionetworks.repo.manager.table.metadata.ObjectDataProvider;
import org.sagebionetworks.repo.manager.table.metadata.ObjectDataProviderFactory;
import org.sagebionetworks.repo.model.IdAndChecksum;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.model.message.ChangeType;
import org.sagebionetworks.repo.model.table.ObjectDataDTO;
import org.sagebionetworks.repo.model.table.ReplicationType;
import org.sagebionetworks.repo.model.table.SubType;
import org.sagebionetworks.repo.model.table.ViewObjectType;
import org.sagebionetworks.repo.model.table.ViewScopeType;
import org.sagebionetworks.table.cluster.view.filter.HierarchicaFilter;
import org.sagebionetworks.table.cluster.view.filter.ViewFilter;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.transaction.TransactionStatus;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

@ExtendWith(MockitoExtension.class)
public class ReplicationManagerTest {

	@Mock
	private ObjectDataProviderFactory mockObjectDataProviderFactory;
	@Mock
	private ReplicationMessageManager mockReplicationMessageManager;
	@Mock
	private TableIndexConnectionFactory mockIndexConnectionFactory;
	@Mock
	private TableIndexManager mockTableIndexManager;
	@Mock
	private TableManagerSupport mockTableManagerSupport;
	@Mock
	private MetadataIndexProviderFactory mockIndexProviderFactory;
	@Mock
	private LoggerProvider mockLoggerProvider;
	@Mock
	private Logger mockLogger;

	private ReplicationManagerImpl manager;

	private ReplicationManagerImpl managerSpy;

	@Mock
	private TransactionStatus transactionStatus;

	@Mock
	private ObjectDataProvider mockObjectDataProvider;

	@Captor
	private ArgumentCaptor<Iterator<ObjectDataDTO>> iteratorCaptor;

	private List<ChangeMessage> changes;

	private ReplicationType mainType;
	private IdAndVersion viewId;
	private long typeMask;
	private ViewScopeType viewScopeType;

	@BeforeEach
	public void before() {
		when(mockLoggerProvider.getLogger(any())).thenReturn(mockLogger);
		manager = new ReplicationManagerImpl(mockObjectDataProviderFactory, mockTableManagerSupport,
				mockReplicationMessageManager, mockIndexConnectionFactory, mockIndexProviderFactory,
				mockLoggerProvider);
		managerSpy = Mockito.spy(manager);
		ChangeMessage update = new ChangeMessage();
		update.setChangeType(ChangeType.UPDATE);
		update.setObjectType(ObjectType.ENTITY);
		update.setObjectId("111");
		ChangeMessage create = new ChangeMessage();
		create.setChangeType(ChangeType.CREATE);
		create.setObjectType(ObjectType.ENTITY);
		create.setObjectId("222");
		ChangeMessage delete = new ChangeMessage();
		delete.setChangeType(ChangeType.DELETE);
		delete.setObjectType(ObjectType.ENTITY);
		delete.setObjectId("333");
		changes = ImmutableList.of(update, create, delete);

		mainType = ReplicationType.ENTITY;
		viewId = IdAndVersion.parse("syn123");
		typeMask = 0x1;
		viewScopeType = new ViewScopeType(ViewObjectType.ENTITY, typeMask);
	}

	@Test
	public void testGroupByObjectType() {
		// Call under test
		Map<ReplicationType, ReplicationDataGroup> result = manager.groupByObjectType(changes);

		assertEquals(1, result.size());

		ReplicationDataGroup group = result.get(mainType);
		assertNotNull(group);

		List<Long> expectedDeleteIds = ImmutableList.of(333L);
		List<Long> expectedCreateOrUpdateIds = ImmutableList.of(111L, 222L);

		assertEquals(expectedDeleteIds, group.getToDeleteIds());
		assertEquals(expectedCreateOrUpdateIds, group.getCreateOrUpdateIds());
	}

	@Test
	public void testGroupByObjectTypeWithUnsupportedType() {

		ChangeMessage message = new ChangeMessage();
		message.setObjectType(ObjectType.USER_PROFILE);
		message.setObjectId("123");
		message.setChangeType(ChangeType.UPDATE);

		changes = new ArrayList<>(changes);

		changes.add(message);

		// Call under test
		Map<ReplicationType, ReplicationDataGroup> result = manager.groupByObjectType(changes);

		assertEquals(1, result.size());

		ReplicationDataGroup group = result.get(mainType);
		assertNotNull(group);

		List<Long> expectedDeleteIds = ImmutableList.of(333L);
		List<Long> expectedCreateOrUpdateIds = ImmutableList.of(111L, 222L);

		assertEquals(expectedDeleteIds, group.getToDeleteIds());
		assertEquals(expectedCreateOrUpdateIds, group.getCreateOrUpdateIds());
	}

	@Test
	public void testReplicateChanges() throws RecoverableMessageException, Exception {

		int count = 2;
		List<ObjectDataDTO> entityData = createEntityDtos(count);

		List<Long> expectedDeleteIds = ImmutableList.of(333L);
		List<Long> expectedCreateOrUpdateIds = ImmutableList.of(111L, 222L);

		when(mockIndexConnectionFactory.connectToFirstIndex()).thenReturn(mockTableIndexManager);
		when(mockObjectDataProviderFactory.getObjectDataProvider(any())).thenReturn(mockObjectDataProvider);
		when(mockObjectDataProvider.getObjectData(any(), anyInt())).thenReturn(entityData.iterator());

		// call under test
		manager.replicate(changes);

		verify(mockIndexConnectionFactory).connectToFirstIndex();
		verify(mockObjectDataProviderFactory).getObjectDataProvider(mainType);
		verify(mockObjectDataProvider).getObjectData(expectedCreateOrUpdateIds,
				ReplicationManagerImpl.MAX_ANNOTATION_CHARS);
		verify(mockTableIndexManager).deleteObjectData(mainType, expectedDeleteIds);
		verify(mockTableIndexManager).updateObjectReplication(eq(mainType), iteratorCaptor.capture());
		List<ObjectDataDTO> actualList = ImmutableList.copyOf(iteratorCaptor.getValue());
		assertEquals(entityData, actualList);
	}

	@Test
	public void testReplicateSingle() {
		String entityId = "syn123";
		List<Long> entityids = Collections.singletonList(KeyFactory.stringToKey(entityId));

		int count = 1;
		List<ObjectDataDTO> entityData = createEntityDtos(count);

		List<Long> expectedDeleteIds = Collections.emptyList();

		when(mockIndexConnectionFactory.connectToFirstIndex()).thenReturn(mockTableIndexManager);
		when(mockObjectDataProviderFactory.getObjectDataProvider(any())).thenReturn(mockObjectDataProvider);
		when(mockObjectDataProvider.getObjectData(any(), anyInt())).thenReturn(entityData.iterator());

		// call under test
		manager.replicate(mainType, entityId);

		verify(mockIndexConnectionFactory).connectToFirstIndex();
		verify(mockObjectDataProviderFactory).getObjectDataProvider(mainType);
		verify(mockObjectDataProvider).getObjectData(entityids, ReplicationManagerImpl.MAX_ANNOTATION_CHARS);
		verify(mockTableIndexManager).deleteObjectData(mainType, expectedDeleteIds);
		verify(mockTableIndexManager).updateObjectReplication(eq(mainType), iteratorCaptor.capture());
		List<ObjectDataDTO> actualList = ImmutableList.copyOf(iteratorCaptor.getValue());
		assertEquals(entityData, actualList);
	}

	/**
	 * Test helper
	 * 
	 * @param count
	 * @return
	 */
	List<ObjectDataDTO> createEntityDtos(int count) {
		List<ObjectDataDTO> dtos = new LinkedList<>();
		for (int i = 0; i < count; i++) {
			ObjectDataDTO dto = new ObjectDataDTO();
			dto.setId(new Long(i));
			dto.setBenefactorId(new Long(i - 1));
			dtos.add(dto);
		}
		return dtos;
	}

	@Test
	public void testReconcileWithLockNotExpired() {
		when(mockTableManagerSupport.getViewScopeType(viewId)).thenReturn(viewScopeType);
		when(mockIndexConnectionFactory.connectToTableIndex(any())).thenReturn(mockTableIndexManager);
		when(mockTableIndexManager.isViewSynchronizeLockExpired(any(), any())).thenReturn(false);

		// call under test
		managerSpy.reconcile(viewId);

		verify(mockLogger).info("Synchronize lock for view: 'syn123' has not expired.  Will not synchronize.");
		verify(mockTableManagerSupport).getViewScopeType(viewId);
		verify(mockIndexConnectionFactory).connectToTableIndex(viewId);
		verify(mockTableIndexManager).isViewSynchronizeLockExpired(ReplicationType.ENTITY, viewId);
		verify(managerSpy, never()).createReconcileIterator(any(), any(), any());
		verify(mockTableIndexManager, never()).resetViewSynchronizeLock(any(), any());
		verifyZeroInteractions(mockReplicationMessageManager);
	}

	@Test
	public void testReconcileWithLockExpired() {
		when(mockTableManagerSupport.getViewScopeType(viewId)).thenReturn(viewScopeType);
		when(mockIndexConnectionFactory.connectToTableIndex(any())).thenReturn(mockTableIndexManager);
		when(mockTableIndexManager.isViewSynchronizeLockExpired(any(), any())).thenReturn(true);
		doReturn(changes.iterator()).when(managerSpy).createReconcileIterator(any(), any(), any());

		// call under test
		managerSpy.reconcile(viewId);

		verify(mockTableManagerSupport).getViewScopeType(viewId);
		verify(mockIndexConnectionFactory).connectToTableIndex(viewId);
		verify(mockTableIndexManager).isViewSynchronizeLockExpired(ReplicationType.ENTITY, viewId);
		verify(managerSpy).createReconcileIterator(mockTableIndexManager, viewScopeType.getObjectType(),
				viewId.getId());

		verify(mockLogger).info("Found 3 objects out-of-synch between truth and replication for view: 'syn123'.");

		verify(mockTableIndexManager).resetViewSynchronizeLock(ReplicationType.ENTITY, viewId);
		verify(mockReplicationMessageManager).pushChangeMessagesToReplicationQueue(changes);

		verify(mockLogger).info("Finished reconcile for view: 'syn123'.");
	}

	@Test
	public void testReconcileWithLockExpiredAndMultiplePages() {
		when(mockTableManagerSupport.getViewScopeType(viewId)).thenReturn(viewScopeType);
		when(mockIndexConnectionFactory.connectToTableIndex(any())).thenReturn(mockTableIndexManager);
		when(mockTableIndexManager.isViewSynchronizeLockExpired(any(), any())).thenReturn(true);

		int count = ReplicationManagerImpl.MAX_MESSAGE_PAGE_SIZE + 1;
		List<ChangeMessage> changes = createChangeMessages(count);
		doReturn(changes.iterator()).when(managerSpy).createReconcileIterator(any(), any(), any());

		// call under test
		managerSpy.reconcile(viewId);

		verify(mockTableManagerSupport).getViewScopeType(viewId);
		verify(mockIndexConnectionFactory).connectToTableIndex(viewId);
		verify(mockTableIndexManager).isViewSynchronizeLockExpired(ReplicationType.ENTITY, viewId);
		verify(managerSpy).createReconcileIterator(mockTableIndexManager, viewScopeType.getObjectType(),
				viewId.getId());

		verify(mockLogger).info("Found 1000 objects out-of-synch between truth and replication for view: 'syn123'.");
		verify(mockLogger).info("Found 1 objects out-of-synch between truth and replication for view: 'syn123'.");

		verify(mockTableIndexManager).resetViewSynchronizeLock(ReplicationType.ENTITY, viewId);
		verify(mockReplicationMessageManager)
				.pushChangeMessagesToReplicationQueue(changes.subList(0, ReplicationManagerImpl.MAX_MESSAGE_PAGE_SIZE));
		verify(mockReplicationMessageManager).pushChangeMessagesToReplicationQueue(changes.subList(
				ReplicationManagerImpl.MAX_MESSAGE_PAGE_SIZE, ReplicationManagerImpl.MAX_MESSAGE_PAGE_SIZE + 1));

		verify(mockLogger).info("Finished reconcile for view: 'syn123'.");
	}

	@Test
	public void testReconcileWithLockExpiredAndNoChanges() {
		when(mockTableManagerSupport.getViewScopeType(viewId)).thenReturn(viewScopeType);
		when(mockIndexConnectionFactory.connectToTableIndex(any())).thenReturn(mockTableIndexManager);
		when(mockTableIndexManager.isViewSynchronizeLockExpired(any(), any())).thenReturn(true);

		List<ChangeMessage> changes = Collections.emptyList();
		doReturn(changes.iterator()).when(managerSpy).createReconcileIterator(any(), any(), any());

		// call under test
		managerSpy.reconcile(viewId);

		verify(mockTableManagerSupport).getViewScopeType(viewId);
		verify(mockIndexConnectionFactory).connectToTableIndex(viewId);
		verify(mockTableIndexManager).isViewSynchronizeLockExpired(ReplicationType.ENTITY, viewId);
		verify(managerSpy).createReconcileIterator(mockTableIndexManager, viewScopeType.getObjectType(),
				viewId.getId());

		verifyZeroInteractions(mockReplicationMessageManager);

		verify(mockLogger).info("Finished reconcile for view: 'syn123'.");
	}

	@Test
	public void testReconcileWitNullId() {
		viewId = null;

		String message = assertThrows(IllegalArgumentException.class, () -> {
			// call under test
			managerSpy.reconcile(viewId);
		}).getMessage();
		assertEquals("idAndVersion is required.", message);
	}

	@Test
	public void testCreateTruthStream() {
		
		when(mockObjectDataProviderFactory.getObjectDataProvider(any())).thenReturn(mockObjectDataProvider);
		Iterator<IdAndChecksum> it = Arrays.asList(new IdAndChecksum().withId(33L)).iterator();
		when(mockObjectDataProvider.streamOverIdsAndChecksumsForChildren(any(), any(), any())).thenReturn(it);
		
		Long salt = 123L;
		Set<SubType> subTypes = Sets.newHashSet(SubType.file);
		Set<Long> scope = Sets.newHashSet(99L);
		ViewFilter filter = new HierarchicaFilter(ReplicationType.ENTITY, subTypes, scope);
		// call under test
		Iterator<IdAndChecksum> result = manager.createTruthStream(salt, filter);
		assertEquals(result, it);
		
		verify(mockObjectDataProviderFactory).getObjectDataProvider(ReplicationType.ENTITY);
		verify(mockObjectDataProvider).streamOverIdsAndChecksumsForChildren(salt, scope, subTypes);
	}

	/**
	 * Helper to create a batch of ChangeMessage with the size of the given count.
	 * 
	 * @param count
	 * @return
	 */
	List<ChangeMessage> createChangeMessages(int count) {
		List<ChangeMessage> results = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			results.add(new ChangeMessage().setChangeNumber(new Long(i)));
		}
		return results;
	}
}
