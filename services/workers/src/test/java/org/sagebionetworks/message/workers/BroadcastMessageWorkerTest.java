package org.sagebionetworks.message.workers;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.http.client.ClientProtocolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.markdown.MarkdownClientException;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.message.BroadcastMessageManager;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.model.message.ChangeType;
import org.sagebionetworks.util.progress.ProgressCallback;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;

@ExtendWith(MockitoExtension.class)
public class BroadcastMessageWorkerTest {

	@Mock
	private BroadcastMessageManager mockBroadcastManager;
	@Mock
	private UserManager mockUserManager;
	@Mock
	private ProgressCallback mockCallback;
	@InjectMocks
	private BroadcastMessageWorker worker;

	UserInfo adminUserInfo;

	@BeforeEach
	public void before() {
		adminUserInfo = new UserInfo(true, BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
	}

	@Test
	public void testSuccess() throws RecoverableMessageException, Exception {
		when(mockUserManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId())).thenReturn(adminUserInfo);
		ChangeMessage fakeMessage = new ChangeMessage();
		fakeMessage.setChangeType(ChangeType.CREATE);
		fakeMessage.setObjectType(ObjectType.THREAD);
		worker.run(mockCallback, fakeMessage);
		verify(mockUserManager).getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
		verify(mockBroadcastManager).broadcastMessage(any(UserInfo.class), eq(mockCallback), eq(fakeMessage));
	}

	@Test
	public void testRecoverableFailure() throws RecoverableMessageException, Exception {
		when(mockUserManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId())).thenReturn(adminUserInfo);
		ChangeMessage fakeMessage = new ChangeMessage();
		fakeMessage.setChangeType(ChangeType.CREATE);
		fakeMessage.setObjectType(ObjectType.THREAD);
		doThrow(new MarkdownClientException(500, ""))
				.when(mockBroadcastManager).broadcastMessage(adminUserInfo, mockCallback, fakeMessage);
		
		assertThrows(RecoverableMessageException.class, () -> {			
			worker.run(mockCallback, fakeMessage);
		});
	}

	@Test
	public void testNonRecoverableFailure() throws RecoverableMessageException, Exception {
		when(mockUserManager.getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId())).thenReturn(adminUserInfo);
		ChangeMessage fakeMessage = new ChangeMessage();
		fakeMessage.setChangeType(ChangeType.CREATE);
		fakeMessage.setObjectType(ObjectType.THREAD);
		doThrow(new ClientProtocolException())
				.when(mockBroadcastManager).broadcastMessage(adminUserInfo, mockCallback, fakeMessage);
		worker.run(mockCallback, fakeMessage);
		verify(mockUserManager).getUserInfo(BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId());
		verify(mockBroadcastManager).broadcastMessage(adminUserInfo, mockCallback, fakeMessage);
	}

	@Test
	public void testSkipBoardcastForThreadUpdateEvent() throws RecoverableMessageException, Exception {
		ChangeMessage fakeMessage = new ChangeMessage();
		fakeMessage.setChangeType(ChangeType.UPDATE);
		fakeMessage.setObjectType(ObjectType.THREAD);
		worker.run(mockCallback, fakeMessage);
		verify(mockUserManager, never()).getUserInfo(any());
		verify(mockBroadcastManager, never()).broadcastMessage(any(),any(),any());
	}

	@Test
	public void testSkipBoardcastForForumUpdateEvent() throws RecoverableMessageException, Exception {
		ChangeMessage fakeMessage = new ChangeMessage();
		fakeMessage.setChangeType(ChangeType.UPDATE);
		fakeMessage.setObjectType(ObjectType.REPLY);
		worker.run(mockCallback, fakeMessage);
		verify(mockUserManager, never()).getUserInfo(any());
		verify(mockBroadcastManager, never()).broadcastMessage(any(),any(),any());
	}

	@Test
	public void testSkipBoardcastForSubmissionUpdateEvent() throws RecoverableMessageException, Exception {
		ChangeMessage fakeMessage = new ChangeMessage();
		fakeMessage.setChangeType(ChangeType.UPDATE);
		fakeMessage.setObjectType(ObjectType.DATA_ACCESS_SUBMISSION);
		worker.run(mockCallback, fakeMessage);
		verify(mockUserManager, never()).getUserInfo(any());
		verify(mockBroadcastManager, never()).broadcastMessage(any(), any(), any());
	}

	@Test
	public void testSkipBoardcastForSubmissionStatusCreateEvent() throws RecoverableMessageException, Exception {
		ChangeMessage fakeMessage = new ChangeMessage();
		fakeMessage.setChangeType(ChangeType.CREATE);
		fakeMessage.setObjectType(ObjectType.DATA_ACCESS_SUBMISSION_STATUS);
		worker.run(mockCallback, fakeMessage);
		verify(mockUserManager, never()).getUserInfo(any());
		verify(mockBroadcastManager, never()).broadcastMessage(any(),any(),any());
	}
}
