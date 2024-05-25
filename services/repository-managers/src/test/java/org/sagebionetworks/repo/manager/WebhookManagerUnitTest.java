package org.sagebionetworks.repo.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlListDAO;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.NextPageToken;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.auth.AuthorizationStatus;
import org.sagebionetworks.repo.model.dbo.dao.webhook.WebhookDao;
import org.sagebionetworks.repo.model.dbo.dao.webhook.WebhookVerificationDao;
import org.sagebionetworks.repo.model.webhook.ListUserWebhooksRequest;
import org.sagebionetworks.repo.model.webhook.ListUserWebhooksResponse;
import org.sagebionetworks.repo.model.webhook.VerifyWebhookRequest;
import org.sagebionetworks.repo.model.webhook.VerifyWebhookResponse;
import org.sagebionetworks.repo.model.webhook.Webhook;
import org.sagebionetworks.repo.model.webhook.WebhookObjectType;
import org.sagebionetworks.repo.model.webhook.WebhookVerification;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.Clock;

import com.amazonaws.regions.Regions;

@ExtendWith(MockitoExtension.class)
public class WebhookManagerUnitTest {

	@Mock
	WebhookDao mockWebhookDao;

	@Mock
	WebhookVerificationDao mockWebhookVerificationDao;

	@Mock
	AccessControlListDAO mockAclDao;

	@Mock
	UserManager mockUserManager;

	@Mock
	Clock mockClock;

	@Mock
	IdGenerator mockIdGenerator;

	@Spy
	@InjectMocks
	WebhookManagerImpl webhookManagerSpy;

	UserInfo userInfo;
	UserInfo adminUserInfo;
	UserInfo anonymousUserInfo;
	UserInfo unauthorizedUserInfo;
	long userId = 123L;
	long adminUserId = BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();
	long anonymousUserId = BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId();
	long unauthorizedUserId = 789L;
	String userIdAsString = String.valueOf(userId);

	Date currentDate;
	Date pastDate;
	Date futureDate;

	String webhookId = "101010";
	Webhook webhook;
	Webhook updatedWebhook;
	String objectId = "syn2024";
	String anotherObjectId = "syn2009";
	WebhookObjectType webhookObjectType = WebhookObjectType.ENTITY;
	ObjectType objectType = ObjectType.valueOf(webhookObjectType.name());
	String validApiGatewayEndpoint = "https://abcd1234.execute-api.us-east-1.amazonaws.com/prod";
	String anotherValidApiGatewayEndpoint = "https://vxyz5678.execute-api.us-west-2.amazonaws.com/prod";
	String invalidInvokeEndpoint = "https://invalidEndpoint.com";
	String validVerificationCode = WebhookManagerImpl.VERIFICATION_CODE_CHARACTERS.substring(0, 1)
			.repeat(WebhookManagerImpl.VERIFICATION_CODE_LENGTH);
	String invalidVerificationCode = "invalid";

	@BeforeEach
	public void before() {
		userInfo = new UserInfo(false, userId);
		adminUserInfo = new UserInfo(true, adminUserId);
		anonymousUserInfo = new UserInfo(false, anonymousUserId);
		unauthorizedUserInfo = new UserInfo(false, unauthorizedUserId);

		// mock the clock
		when(mockClock.now()).thenReturn(new GregorianCalendar(2024, Calendar.MAY, 21).getTime());
		currentDate = mockClock.now();
		pastDate = new Date(currentDate.getTime() - 10 ^ 6);
		futureDate = new Date(currentDate.getTime() + 10 ^ 6);

		webhook = new Webhook().setWebhookId(webhookId).setObjectId(objectId).setObjectType(webhookObjectType)
				.setUserId(userIdAsString).setInvokeEndpoint(validApiGatewayEndpoint).setIsVerified(false)
				.setIsAuthenticationEnabled(true).setIsWebhookEnabled(true).setEtag(UUID.randomUUID().toString())
				.setCreatedBy(userIdAsString).setModifiedBy(userIdAsString).setCreatedOn(currentDate)
				.setModifiedOn(currentDate);

		updatedWebhook = new Webhook().setWebhookId(webhookId).setObjectId(anotherObjectId) // updated
				.setObjectType(webhookObjectType).setUserId(userIdAsString)
				.setInvokeEndpoint(anotherValidApiGatewayEndpoint) // updated
				.setIsVerified(false).setIsAuthenticationEnabled(true).setIsWebhookEnabled(true)
				.setEtag(UUID.randomUUID().toString()) // updated
				.setCreatedBy(userIdAsString).setModifiedBy(userIdAsString).setCreatedOn(currentDate)
				.setModifiedOn(currentDate);
	}

	@Test
	public void testCreateWebhook() {
		Webhook toCreate = new Webhook().setObjectId(objectId).setObjectType(webhookObjectType)
				.setInvokeEndpoint(validApiGatewayEndpoint).setIsWebhookEnabled(true).setIsAuthenticationEnabled(true);

		doNothing().when(webhookManagerSpy).validateCreateOrUpdateArguments(any(), any());
		when(mockAclDao.canAccess(any(UserInfo.class), any(), any(), any()))
				.thenReturn(AuthorizationStatus.authorized());
		when(mockIdGenerator.generateNewId(any())).thenReturn(Long.valueOf(webhookId));
		when(mockWebhookDao.createWebhook(any())).thenReturn(webhook);
		doNothing().when(webhookManagerSpy).generateAndSendWebhookVerification(any());

		// Call under test
		Webhook result = webhookManagerSpy.createWebhook(userInfo, toCreate);

		assertEquals(webhook, result);

		verify(webhookManagerSpy).validateCreateOrUpdateArguments(userInfo, toCreate);
		verify(mockAclDao).canAccess(userInfo, objectId, objectType, ACCESS_TYPE.READ);
		verify(mockIdGenerator).generateNewId(IdType.WEBHOOK_ID);
		verify(mockWebhookDao).createWebhook(toCreate);
		verify(webhookManagerSpy).generateAndSendWebhookVerification(webhook);
	}

	@Test
	public void testCreateWebhookWithoutReadPermissionOnObject() {
		String accessDeniedMessage = String.format("You do not have %s permission for %s : %s", ACCESS_TYPE.READ,
				objectType, objectId);
		Webhook toCreate = new Webhook().setObjectId(objectId).setObjectType(webhookObjectType)
				.setInvokeEndpoint(validApiGatewayEndpoint).setIsWebhookEnabled(true).setIsAuthenticationEnabled(true);

		when(mockAclDao.canAccess(any(UserInfo.class), any(), any(), any()))
				.thenReturn(AuthorizationStatus.accessDenied(accessDeniedMessage));

		String errorMessage = assertThrows(UnauthorizedException.class, () -> {
			// Call under test
			webhookManagerSpy.createWebhook(userInfo, toCreate);
		}).getMessage();

		assertEquals(accessDeniedMessage, errorMessage);

		verify(webhookManagerSpy).validateCreateOrUpdateArguments(userInfo, toCreate);
		verify(mockAclDao).canAccess(userInfo, objectId, objectType, ACCESS_TYPE.READ);
		verify(mockIdGenerator, never()).generateNewId(any());
		verify(mockWebhookDao, never()).createWebhook(any());
		verify(webhookManagerSpy, never()).generateAndSendWebhookVerification(any(), any());
	}

	@Test
	public void testCreateWebhookWithEnabledsDefaultingToTrue() {
		Webhook toCreate = new Webhook().setObjectId(objectId).setObjectType(webhookObjectType)
				.setInvokeEndpoint(validApiGatewayEndpoint).setIsWebhookEnabled(null).setIsAuthenticationEnabled(null);

		doNothing().when(webhookManagerSpy).validateCreateOrUpdateArguments(any(), any());
		when(mockAclDao.canAccess(any(UserInfo.class), any(), any(), any()))
				.thenReturn(AuthorizationStatus.authorized());
		when(mockIdGenerator.generateNewId(any())).thenReturn(Long.valueOf(webhookId));
		when(mockWebhookDao.createWebhook(any())).thenReturn(webhook);
		doNothing().when(webhookManagerSpy).generateAndSendWebhookVerification(any());

		// Call under test
		Webhook result = webhookManagerSpy.createWebhook(userInfo, toCreate);

		assertEquals(webhook, result);

		verify(webhookManagerSpy).validateCreateOrUpdateArguments(userInfo, toCreate);
		verify(mockAclDao).canAccess(userInfo, objectId, objectType, ACCESS_TYPE.READ);
		verify(mockIdGenerator).generateNewId(IdType.WEBHOOK_ID);
		verify(mockWebhookDao).createWebhook(toCreate);
		verify(webhookManagerSpy).generateAndSendWebhookVerification(webhook);
	}

	@Test
	public void testCreateWebhookWithEnabledsSetToFalse() {
		webhook.setIsWebhookEnabled(false).setIsAuthenticationEnabled(false);
		Webhook toCreate = new Webhook().setObjectId(objectId).setObjectType(webhookObjectType)
				.setInvokeEndpoint(validApiGatewayEndpoint).setIsWebhookEnabled(false)
				.setIsAuthenticationEnabled(false);

		doNothing().when(webhookManagerSpy).validateCreateOrUpdateArguments(any(), any());
		when(mockAclDao.canAccess(any(UserInfo.class), any(), any(), any()))
				.thenReturn(AuthorizationStatus.authorized());
		when(mockIdGenerator.generateNewId(any())).thenReturn(Long.valueOf(webhookId));
		when(mockWebhookDao.createWebhook(any())).thenReturn(webhook);
		doNothing().when(webhookManagerSpy).generateAndSendWebhookVerification(any());

		// Call under test
		Webhook result = webhookManagerSpy.createWebhook(userInfo, toCreate);

		assertEquals(webhook, result);

		verify(webhookManagerSpy).validateCreateOrUpdateArguments(userInfo, toCreate);
		verify(mockAclDao).canAccess(userInfo, objectId, objectType, ACCESS_TYPE.READ);
		verify(mockIdGenerator).generateNewId(IdType.WEBHOOK_ID);
		verify(mockWebhookDao).createWebhook(toCreate);
		verify(webhookManagerSpy).generateAndSendWebhookVerification(webhook);
	}

	@Test
	public void testGetWebhook() {
		when(mockWebhookDao.getWebhook(any())).thenReturn(webhook);
		doNothing().when(webhookManagerSpy).validateUserIsAdminOrWebhookOwner(any(), any());

		// Call under test
		Webhook result = webhookManagerSpy.getWebhook(userInfo, webhookId);

		assertEquals(webhook, result);

		verify(mockWebhookDao).getWebhook(webhookId);
		verify(webhookManagerSpy).validateUserIsAdminOrWebhookOwner(userInfo, webhook);
	}

	@Test
	public void testGetWebhookWithNullUserInfo() {
		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.getWebhook(null, webhookId);
		}).getMessage();

		assertEquals("userInfo is required.", errorMessage);
	}

	@Test
	public void testGetWebhookWithNullWebhookId() {
		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.getWebhook(userInfo, null);
		}).getMessage();

		assertEquals("webhookId is required.", errorMessage);
	}

	@Test
	public void testUpdateWebhook() {
		Webhook updateWith = new Webhook().setWebhookId(webhookId).setObjectId(updatedWebhook.getObjectId()) // update
				.setObjectType(updatedWebhook.getObjectType()) // update
				.setInvokeEndpoint(updatedWebhook.getInvokeEndpoint()) // update
				.setEtag(webhook.getEtag());

		doNothing().when(webhookManagerSpy).validateCreateOrUpdateArguments(any(), any());
		when(mockAclDao.canAccess(any(UserInfo.class), any(), any(), any()))
				.thenReturn(AuthorizationStatus.authorized());
		when(mockWebhookDao.getWebhookForUpdate(any())).thenReturn(webhook);
		doNothing().when(webhookManagerSpy).validateUserIsAdminOrWebhookOwner(any(), any());
		when(mockWebhookDao.updateWebhook(any())).thenReturn(updatedWebhook);
		doNothing().when(webhookManagerSpy).generateAndSendWebhookVerification(any());

		// Call under test
		Webhook response = webhookManagerSpy.updateWebhook(userInfo, updateWith);

		assertEquals(updatedWebhook, response);

		verify(webhookManagerSpy).validateCreateOrUpdateArguments(userInfo, updateWith);
		verify(mockAclDao).canAccess(userInfo, anotherObjectId, objectType, ACCESS_TYPE.READ);
		verify(mockWebhookDao).getWebhookForUpdate(webhookId);
		verify(webhookManagerSpy).validateUserIsAdminOrWebhookOwner(userInfo, webhook);
		verify(webhookManagerSpy).generateAndSendWebhookVerification(updatedWebhook);
	}

	@Test
	public void testUpdateWebhookWithNullEtag() {
		Webhook updateWith = new Webhook().setEtag(null);

		doNothing().when(webhookManagerSpy).validateCreateOrUpdateArguments(any(), any());

		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.updateWebhook(userInfo, updateWith);
		}).getMessage();

		assertEquals("updateWith.etag is required.", errorMessage);

		verify(webhookManagerSpy).validateCreateOrUpdateArguments(userInfo, updateWith);
	}

	@Test
	public void testUpdateWebhookWithoutReadPermission() {
		String accessDeniedMessage = String.format("You do not have %s permission for %s : %s", ACCESS_TYPE.READ,
				objectType, objectId);
		Webhook updateWith = new Webhook().setWebhookId(webhookId).setObjectId(updatedWebhook.getObjectId()) // update
				.setObjectType(updatedWebhook.getObjectType()) // update
				.setInvokeEndpoint(updatedWebhook.getInvokeEndpoint()) // update
				.setEtag(webhook.getEtag());

		doNothing().when(webhookManagerSpy).validateCreateOrUpdateArguments(any(), any());
		when(mockAclDao.canAccess(any(UserInfo.class), any(), any(), any()))
				.thenReturn(AuthorizationStatus.accessDenied(accessDeniedMessage));

		String errorMessage = assertThrows(UnauthorizedException.class, () -> {
			// Call under test
			webhookManagerSpy.updateWebhook(userInfo, updateWith);
		}).getMessage();

		assertEquals(accessDeniedMessage, errorMessage);

		verify(webhookManagerSpy).validateCreateOrUpdateArguments(userInfo, updateWith);
		verify(mockAclDao).canAccess(userInfo, anotherObjectId, objectType, ACCESS_TYPE.READ);
		verify(mockWebhookDao, never()).getWebhookForUpdate(any());
		verify(webhookManagerSpy, never()).validateUserIsAdminOrWebhookOwner(any(), any());
		verify(mockWebhookDao, never()).updateWebhook(any());
		verify(webhookManagerSpy, never()).generateAndSendWebhookVerification(any(), any());
	}

	@Test
	public void testUpdateWebhookWithStaleEtag() {
		Webhook updateWith = new Webhook().setWebhookId(webhookId).setObjectId(updatedWebhook.getObjectId()) // update
				.setObjectType(updatedWebhook.getObjectType()) // update
				.setInvokeEndpoint(updatedWebhook.getInvokeEndpoint()) // update
				.setEtag(UUID.randomUUID().toString());

		doNothing().when(webhookManagerSpy).validateCreateOrUpdateArguments(any(), any());
		when(mockAclDao.canAccess(any(UserInfo.class), any(), any(), any()))
				.thenReturn(AuthorizationStatus.authorized());
		when(mockWebhookDao.getWebhookForUpdate(any())).thenReturn(webhook);
		doNothing().when(webhookManagerSpy).validateUserIsAdminOrWebhookOwner(any(), any());

		String errorMessage = assertThrows(ConflictingUpdateException.class, () -> {
			// Call under test
			webhookManagerSpy.updateWebhook(userInfo, updateWith);
		}).getMessage();

		assertEquals(String.format(WebhookManagerImpl.CONFLICTING_UPDATE_MESSAGE, webhook.getWebhookId()),
				errorMessage);

		verify(webhookManagerSpy).validateCreateOrUpdateArguments(userInfo, updateWith);
		verify(mockAclDao).canAccess(userInfo, anotherObjectId, objectType, ACCESS_TYPE.READ);
		verify(mockWebhookDao).getWebhookForUpdate(webhookId);
		verify(webhookManagerSpy).validateUserIsAdminOrWebhookOwner(userInfo, webhook);
		verify(mockWebhookDao, never()).updateWebhook(any());
		verify(webhookManagerSpy, never()).generateAndSendWebhookVerification(any(), any());
	}

	@Test
	public void testUpdateWebhookWithEnableds() {
		updatedWebhook.setIsWebhookEnabled(false).setIsAuthenticationEnabled(false);
		Webhook updateWith = new Webhook().setWebhookId(webhookId).setObjectId(updatedWebhook.getObjectId()) // update
				.setObjectType(updatedWebhook.getObjectType()) // update
				.setInvokeEndpoint(updatedWebhook.getInvokeEndpoint()) // update
				.setIsWebhookEnabled(false) // update
				.setIsAuthenticationEnabled(false) // update
				.setEtag(webhook.getEtag());

		doNothing().when(webhookManagerSpy).validateCreateOrUpdateArguments(any(), any());
		when(mockWebhookDao.getWebhookForUpdate(any())).thenReturn(webhook);
		doNothing().when(webhookManagerSpy).validateUserIsAdminOrWebhookOwner(any(), any());
		when(mockAclDao.canAccess(any(UserInfo.class), any(), any(), any()))
				.thenReturn(AuthorizationStatus.authorized());
		when(mockWebhookDao.updateWebhook(any())).thenReturn(updatedWebhook);
		doNothing().when(webhookManagerSpy).generateAndSendWebhookVerification(any());

		// Call under test
		Webhook response = webhookManagerSpy.updateWebhook(userInfo, updateWith);

		assertEquals(updatedWebhook, response);

		verify(webhookManagerSpy).validateCreateOrUpdateArguments(userInfo, updateWith);
		verify(mockAclDao).canAccess(userInfo, anotherObjectId, objectType, ACCESS_TYPE.READ);
		verify(mockWebhookDao).getWebhookForUpdate(webhookId);
		verify(webhookManagerSpy).validateUserIsAdminOrWebhookOwner(userInfo, webhook);
		verify(webhookManagerSpy).generateAndSendWebhookVerification(updatedWebhook);
	}

	@Test
	public void testDeleteWebhook() {
		doReturn(webhook).when(webhookManagerSpy).getWebhook(any(), any());

		// Call under test
		webhookManagerSpy.deleteWebhook(userInfo, webhookId);

		verify(webhookManagerSpy).getWebhook(userInfo, webhookId);
		verify(mockWebhookDao).deleteWebhook(webhookId);
	}

	@Test
	public void testDeleteWebhookAsAdmin() {
		when(mockUserManager.getUserInfo(any())).thenReturn(adminUserInfo);
		doNothing().when(webhookManagerSpy).deleteWebhook(any(), any());

		// Call under test
		webhookManagerSpy.deleteWebhookAsAdmin(webhookId);

		verify(mockUserManager).getUserInfo(adminUserId);
		verify(webhookManagerSpy).deleteWebhook(adminUserInfo, webhookId);
	}

	@Test
	public void testVerifyWebhookWithValidCode() {
		WebhookVerification verification = new WebhookVerification().setWebhookId(webhookId)
				.setVerificationCode(validVerificationCode).setExpiresOn(futureDate).setAttempts(0L);

		VerifyWebhookRequest request = new VerifyWebhookRequest().setWebhookId(webhookId)
				.setVerificationCode(validVerificationCode);

		doReturn(webhook).when(webhookManagerSpy).lockAndValidateOwner(any(), any());
		when(mockWebhookVerificationDao.getWebhookVerification(any())).thenReturn(verification);
		when(mockWebhookVerificationDao.incrementAttempts(any())).thenReturn(verification.getAttempts() + 1L);
		doReturn(webhook.setIsVerified(true)).when(mockWebhookDao).updateWebhook(any());

		// Call under test
		VerifyWebhookResponse response = webhookManagerSpy.verifyWebhook(userInfo, request);

		assertTrue(response.getIsValid());
		assertNull(response.getInvalidReason());

		verify(webhookManagerSpy).lockAndValidateOwner(userInfo, webhookId);
		verify(mockWebhookVerificationDao).getWebhookVerification(webhookId);
		verify(mockWebhookVerificationDao).incrementAttempts(webhookId);
		verify(webhookManagerSpy, never()).deleteWebhookAsAdmin(any());
		verify(mockWebhookDao).updateWebhook(webhook.setIsVerified(true));
	}

	@Test
	public void testVerifyWebhookWithExactlyOneLessThanMaxAttempts() {
		WebhookVerification verification = new WebhookVerification().setWebhookId(webhookId)
				.setVerificationCode(validVerificationCode).setExpiresOn(futureDate)
				.setAttempts(WebhookManagerImpl.MAXIMUM_VERIFICATION_ATTEMPTS - 1L);

		VerifyWebhookRequest request = new VerifyWebhookRequest().setWebhookId(webhookId)
				.setVerificationCode(validVerificationCode);

		doReturn(webhook).when(webhookManagerSpy).lockAndValidateOwner(any(), any());
		when(mockWebhookVerificationDao.getWebhookVerification(any())).thenReturn(verification);
		when(mockWebhookVerificationDao.incrementAttempts(any())).thenReturn(verification.getAttempts() + 1L);
		doReturn(webhook.setIsVerified(true)).when(mockWebhookDao).updateWebhook(any());

		// Call under test
		VerifyWebhookResponse response = webhookManagerSpy.verifyWebhook(userInfo, request);

		assertTrue(response.getIsValid());
		assertNull(response.getInvalidReason());

		verify(webhookManagerSpy).lockAndValidateOwner(userInfo, webhookId);
		verify(mockWebhookVerificationDao).getWebhookVerification(webhookId);
		verify(mockWebhookVerificationDao).incrementAttempts(webhookId);
		verify(webhookManagerSpy, never()).deleteWebhookAsAdmin(any());
		verify(mockWebhookDao).updateWebhook(webhook.setIsVerified(true));
	}

	@Test
	public void testVerifyWebhookWithMoreThanMaxAttempts() {
		WebhookVerification verification = new WebhookVerification().setWebhookId(webhookId)
				.setVerificationCode(validVerificationCode).setExpiresOn(futureDate)
				.setAttempts(WebhookManagerImpl.MAXIMUM_VERIFICATION_ATTEMPTS);

		VerifyWebhookRequest request = new VerifyWebhookRequest().setWebhookId(webhookId)
				.setVerificationCode(validVerificationCode);

		doReturn(webhook).when(webhookManagerSpy).lockAndValidateOwner(any(), any());
		when(mockWebhookVerificationDao.getWebhookVerification(any())).thenReturn(verification);
		when(mockWebhookVerificationDao.incrementAttempts(any())).thenReturn(verification.getAttempts() + 1L);
		doNothing().when(webhookManagerSpy).deleteWebhookAsAdmin(any());

		// Call under test
		VerifyWebhookResponse response = webhookManagerSpy.verifyWebhook(userInfo, request);

		assertFalse(response.getIsValid());
		assertEquals(WebhookManagerImpl.EXCEEDED_MAXIMUM_ATTEMPTS, response.getInvalidReason());

		verify(webhookManagerSpy).lockAndValidateOwner(userInfo, webhookId);
		verify(mockWebhookVerificationDao).getWebhookVerification(webhookId);
		verify(mockWebhookVerificationDao).incrementAttempts(webhookId);
		verify(webhookManagerSpy).deleteWebhookAsAdmin(webhookId);
		verify(mockWebhookDao, never()).updateWebhook(any());
	}

	@Test
	public void testVerifyWebhookWithInvalidCode() {
		WebhookVerification verification = new WebhookVerification().setWebhookId(webhookId)
				.setVerificationCode(validVerificationCode).setExpiresOn(futureDate).setAttempts(0L);

		VerifyWebhookRequest request = new VerifyWebhookRequest().setWebhookId(webhookId)
				.setVerificationCode(invalidVerificationCode);

		doReturn(webhook).when(webhookManagerSpy).lockAndValidateOwner(any(), any());
		when(mockWebhookVerificationDao.getWebhookVerification(any())).thenReturn(verification);
		when(mockWebhookVerificationDao.incrementAttempts(any())).thenReturn(verification.getAttempts() + 1L);

		// Call under test
		VerifyWebhookResponse response = webhookManagerSpy.verifyWebhook(userInfo, request);

		assertFalse(response.getIsValid());
		assertEquals(WebhookManagerImpl.INVALID_VERIFICATION_CODE_MESSAGE, response.getInvalidReason());

		verify(webhookManagerSpy).lockAndValidateOwner(userInfo, webhookId);
		verify(mockWebhookVerificationDao).getWebhookVerification(webhookId);
		verify(mockWebhookVerificationDao).incrementAttempts(webhookId);
		verify(webhookManagerSpy, never()).deleteWebhookAsAdmin(any());
		verify(mockWebhookDao, never()).updateWebhook(any());
	}

	@Test
	public void testVerifyWebhookWithExpiredCode() {
		WebhookVerification verification = new WebhookVerification().setWebhookId(webhookId)
				.setVerificationCode(validVerificationCode).setExpiresOn(pastDate).setAttempts(0L);

		VerifyWebhookRequest request = new VerifyWebhookRequest().setWebhookId(webhookId)
				.setVerificationCode(validVerificationCode);

		doReturn(webhook).when(webhookManagerSpy).lockAndValidateOwner(any(), any());
		when(mockWebhookVerificationDao.getWebhookVerification(any())).thenReturn(verification);
		when(mockWebhookVerificationDao.incrementAttempts(any())).thenReturn(verification.getAttempts() + 1L);

		// Call under test
		VerifyWebhookResponse response = webhookManagerSpy.verifyWebhook(userInfo, request);

		assertFalse(response.getIsValid());
		assertEquals(WebhookManagerImpl.EXPIRED_VERIFICATION_CODE_MESSAGE, response.getInvalidReason());

		verify(webhookManagerSpy).lockAndValidateOwner(userInfo, webhookId);
		verify(mockWebhookVerificationDao).getWebhookVerification(webhookId);
		verify(mockWebhookVerificationDao).incrementAttempts(webhookId);
		verify(webhookManagerSpy, never()).deleteWebhookAsAdmin(any());
		verify(mockWebhookDao, never()).updateWebhook(any());
	}

	@Test
	public void testVerifyWebhookWithInvalidAndExpiredCode() {
		String validCode = "someCode";
		WebhookVerification verification = new WebhookVerification().setWebhookId(webhookId)
				.setVerificationCode(validCode).setExpiresOn(new GregorianCalendar(1000, Calendar.JANUARY, 1).getTime())
				.setAttempts(0L);

		VerifyWebhookRequest request = new VerifyWebhookRequest().setWebhookId(webhookId)
				.setVerificationCode(invalidVerificationCode);

		doReturn(webhook).when(webhookManagerSpy).lockAndValidateOwner(any(), any());
		when(mockWebhookVerificationDao.getWebhookVerification(any())).thenReturn(verification);
		when(mockWebhookVerificationDao.incrementAttempts(any())).thenReturn(verification.getAttempts() + 1L);

		// Call under test
		VerifyWebhookResponse response = webhookManagerSpy.verifyWebhook(userInfo, request);

		assertFalse(response.getIsValid());
		assertEquals(WebhookManagerImpl.INVALID_VERIFICATION_CODE_MESSAGE, response.getInvalidReason());

		verify(webhookManagerSpy).lockAndValidateOwner(userInfo, webhookId);
		verify(mockWebhookVerificationDao).getWebhookVerification(webhookId);
		verify(mockWebhookVerificationDao).incrementAttempts(webhookId);
		verify(webhookManagerSpy, never()).deleteWebhookAsAdmin(any());
		verify(mockWebhookDao, never()).updateWebhook(any());
	}

	@Test
	public void testVerifyWebhookWithNullUserInfo() {
		VerifyWebhookRequest request = new VerifyWebhookRequest().setWebhookId(webhookId)
				.setVerificationCode(invalidVerificationCode);

		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.verifyWebhook(null, request);
		}).getMessage();

		assertEquals("userInfo is required.", errorMessage);
	}

	@Test
	public void testVerifyWebhookWithNullRequest() {
		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.verifyWebhook(userInfo, null);
		}).getMessage();

		assertEquals("verifyWebhookRequest is required.", errorMessage);
	}

	@Test
	public void testVerifyWebhookWithNullWebhookId() {
		VerifyWebhookRequest request = new VerifyWebhookRequest().setWebhookId(null)
				.setVerificationCode(validVerificationCode);

		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.verifyWebhook(userInfo, request);
		}).getMessage();

		assertEquals("verifyWebhookRequest.webhookId is required.", errorMessage);
	}

	@Test
	public void testVerifyWebhookWithNullVerificationCode() {
		VerifyWebhookRequest request = new VerifyWebhookRequest().setWebhookId(webhookId).setVerificationCode(null);

		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.verifyWebhook(userInfo, request);
		}).getMessage();

		assertEquals("verifyWebhookRequest.verificationCode is required.", errorMessage);
	}

	@Test
	public void testListUserWebhooks() {
		ListUserWebhooksRequest request = new ListUserWebhooksRequest().setUserId(userIdAsString);

		List<Webhook> page = new ArrayList<Webhook>();
		for (int i = 0; i < NextPageToken.MAX_LIMIT + 1; i++) {
			page.add(new Webhook().setUserId(userIdAsString));
		}
		ListUserWebhooksResponse expectedResponse = new ListUserWebhooksResponse().setPage(page)
				.setNextPageToken("50a50");
		long expectedLimit = NextPageToken.DEFAULT_LIMIT + 1;
		long expectedOffset = NextPageToken.DEFAULT_OFFSET;

		when(mockWebhookDao.listUserWebhooks(any(), anyLong(), anyLong())).thenReturn(page);

		// Call under test
		ListUserWebhooksResponse response = webhookManagerSpy.listUserWebhooks(userInfo, request);

		assertEquals(expectedResponse, response);

		verify(mockWebhookDao).listUserWebhooks(userId, expectedLimit, expectedOffset);
	}

	@Test
	public void testListUserWebhooksWithNullUserInfo() {
		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.listUserWebhooks(null, new ListUserWebhooksRequest());
		}).getMessage();

		assertEquals("userInfo is required.", errorMessage);
	}

	@Test
	public void testListUserWebhooksWithNullRequest() {
		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.listUserWebhooks(userInfo, null);
		}).getMessage();

		assertEquals("listUserWebhooksRequest is required.", errorMessage);
	}

	@Test
	public void testListUserWebhooksWithLessThanOnePage() {
		ListUserWebhooksRequest request = new ListUserWebhooksRequest().setUserId(userIdAsString);

		List<Webhook> page = new ArrayList<Webhook>();
		for (int i = 0; i < Math.max(Math.ceil(NextPageToken.MAX_LIMIT / 3), 3); i++) {
			page.add(new Webhook().setUserId(userIdAsString));
		}
		ListUserWebhooksResponse expectedResponse = new ListUserWebhooksResponse().setPage(page).setNextPageToken(null);
		long expectedLimit = NextPageToken.DEFAULT_LIMIT + 1;
		long expectedOffset = NextPageToken.DEFAULT_OFFSET;

		when(mockWebhookDao.listUserWebhooks(any(), anyLong(), anyLong())).thenReturn(page);

		// Call under test
		ListUserWebhooksResponse response = webhookManagerSpy.listUserWebhooks(userInfo, request);

		assertEquals(expectedResponse, response);

		verify(mockWebhookDao).listUserWebhooks(userId, expectedLimit, expectedOffset);
	}

	@Test
	public void testListUserWebhooksWithZeroWebhooks() {
		ListUserWebhooksRequest request = new ListUserWebhooksRequest().setUserId(userIdAsString);

		List<Webhook> page = new ArrayList<Webhook>();
		ListUserWebhooksResponse expectedResponse = new ListUserWebhooksResponse().setPage(page).setNextPageToken(null);
		long expectedLimit = NextPageToken.DEFAULT_LIMIT + 1;
		long expectedOffset = NextPageToken.DEFAULT_OFFSET;

		when(mockWebhookDao.listUserWebhooks(any(), anyLong(), anyLong())).thenReturn(page);

		// Call under test
		ListUserWebhooksResponse response = webhookManagerSpy.listUserWebhooks(userInfo, request);

		assertEquals(expectedResponse, response);

		verify(mockWebhookDao).listUserWebhooks(userId, expectedLimit, expectedOffset);
	}

	@Test
	public void testListSendableWebhooksForObjectId() {
		List<Webhook> webhooks = createDefaultWebhooksForUsers(List.of(userInfo), 5);

		when(mockWebhookDao.listVerifiedAndEnabledWebhooksForObjectId(objectId, objectType)).thenReturn(webhooks);
		when(mockUserManager.getUserInfo(anyLong())).thenReturn(userInfo);

		// Call under test
		List<Webhook> result = webhookManagerSpy.listSendableWebhooksForObjectId(objectId, webhookObjectType);

		assertEquals(webhooks, result);

		verify(mockWebhookDao).listVerifiedAndEnabledWebhooksForObjectId(objectId, objectType);
		verify(mockUserManager, times(5)).getUserInfo(userId);
	}

	@Test
	public void testListSendableWebhooksForObjectIdWithZeroWebhooks() {
		List<Webhook> webhooks = new ArrayList<>();

		when(mockWebhookDao.listVerifiedAndEnabledWebhooksForObjectId(objectId, objectType)).thenReturn(webhooks);

		// Call under test
		List<Webhook> result = webhookManagerSpy.listSendableWebhooksForObjectId(objectId, webhookObjectType);

		assertEquals(webhooks, result);

		verify(mockWebhookDao).listVerifiedAndEnabledWebhooksForObjectId(objectId, objectType);
		verify(mockUserManager, never()).getUserInfo(any());
	}

	@Test
	public void testListSendableWebhooksForObjectIdWithManyUsers() {
		List<Webhook> webhooks = createDefaultWebhooksForUsers(createUsers(5), 1);

		when(mockWebhookDao.listVerifiedAndEnabledWebhooksForObjectId(objectId, objectType)).thenReturn(webhooks);
		for (Webhook webhook : webhooks) {
			Long currUserId = Long.parseLong(webhook.getUserId());
			when(mockUserManager.getUserInfo(currUserId)).thenReturn(new UserInfo(false, currUserId));
		}

		// Call under test
		List<Webhook> result = webhookManagerSpy.listSendableWebhooksForObjectId(objectId, webhookObjectType);

		assertEquals(webhooks, result);

		verify(mockWebhookDao).listVerifiedAndEnabledWebhooksForObjectId(objectId, objectType);
		for (Webhook webhook : webhooks) {
			verify(mockUserManager).getUserInfo(Long.parseLong(webhook.getUserId()));
		}
	}

	@Test
	public void testListSendableWebhooksForObjectIdWithSomeSendable() {
		List<UserInfo> users = createUsers(5);
		List<Webhook> webhooks = Stream
				.concat(createDefaultWebhooksForUsers(users, 1).stream(),
						createWebhooksForUsers(users, 1, anotherObjectId, objectType, false).stream())
				.collect(Collectors.toList());

		when(mockWebhookDao.listVerifiedAndEnabledWebhooksForObjectId(objectId, objectType)).thenReturn(webhooks);

		// Call under test
		List<Webhook> result = webhookManagerSpy.listSendableWebhooksForObjectId(objectId, webhookObjectType);

		assertEquals(5, result.size());

		verify(mockWebhookDao).listVerifiedAndEnabledWebhooksForObjectId(objectId, objectType);
		for (Webhook webhook : webhooks) {
			verify(mockUserManager, times(2)).getUserInfo(Long.parseLong(webhook.getUserId()));
		}
	}

	@Test
	public void testListSendableWebhooksForObjectIdWithZeroSendable() {
		List<Webhook> webhooks = createWebhooksForUsers(createUsers(5), 1, anotherObjectId, objectType, false);

		when(mockWebhookDao.listVerifiedAndEnabledWebhooksForObjectId(objectId, objectType)).thenReturn(webhooks);

		// Call under test
		List<Webhook> result = webhookManagerSpy.listSendableWebhooksForObjectId(objectId, webhookObjectType);

		assertEquals(0, result.size());

		verify(mockWebhookDao).listVerifiedAndEnabledWebhooksForObjectId(objectId, objectType);
		for (Webhook webhook : webhooks) {
			verify(mockUserManager).getUserInfo(Long.parseLong(webhook.getUserId()));
		}
	}

	@Test
	public void testListSendableWebhooksForObjectIdWithWebhookWithInvalidUserId() {
		String invalidUserId = "invalidUserId";
		Webhook invalidWebhook = new Webhook().setWebhookId(webhookId).setObjectId(objectId)
				.setObjectType(webhookObjectType).setUserId(invalidUserId).setInvokeEndpoint(validApiGatewayEndpoint);
		List<UserInfo> users = createUsers(5);
		List<Webhook> webhooks = new ArrayList<>();
		webhooks.addAll(createDefaultWebhooksForUsers(users, 1)); // 5 sendable
		webhooks.addAll(createWebhooksForUsers(users, 1, anotherObjectId, objectType, false)); // 5 unauthorized
		webhooks.add(invalidWebhook); // 1 invalid userId

		when(mockWebhookDao.listVerifiedAndEnabledWebhooksForObjectId(objectId, objectType)).thenReturn(webhooks);
		doNothing().when(webhookManagerSpy).deleteWebhookAsAdmin(any());

		// Call under test
		List<Webhook> result = webhookManagerSpy.listSendableWebhooksForObjectId(objectId, webhookObjectType);

		assertEquals(5, result.size());

		verify(mockWebhookDao).listVerifiedAndEnabledWebhooksForObjectId(objectId, objectType);
		for (int i = 0; i < 10; i++) {
			verify(mockUserManager, times(2)).getUserInfo(Long.parseLong(webhooks.get(i).getUserId()));
		}
		verify(webhookManagerSpy).deleteWebhookAsAdmin(webhookId);
	}

	@Test
	public void testListSendableWebhooksForObjectIdWithWebhookWithUserNotFound() {
		List<UserInfo> users = createUsers(5);
		List<Webhook> webhooks = new ArrayList<>();
		webhooks.addAll(createDefaultWebhooksForUsers(users, 1)); // 5 sendable
		webhooks.addAll(createWebhooksForUsers(users, 1, anotherObjectId, objectType, false)); // 5 unauthorized
		webhooks.add(webhook); // 1 user not found

		when(mockWebhookDao.listVerifiedAndEnabledWebhooksForObjectId(objectId, objectType)).thenReturn(webhooks);
		when(mockUserManager.getUserInfo(userId)).thenThrow(new NotFoundException("User not found"));
		doNothing().when(webhookManagerSpy).deleteWebhookAsAdmin(any());

		// Call under test
		List<Webhook> result = webhookManagerSpy.listSendableWebhooksForObjectId(objectId, webhookObjectType);

		assertEquals(5, result.size());

		verify(mockWebhookDao).listVerifiedAndEnabledWebhooksForObjectId(objectId, objectType);
		for (int i = 0; i < 10; i++) {
			verify(mockUserManager, times(2)).getUserInfo(Long.parseLong(webhooks.get(i).getUserId()));
		}
		verify(webhookManagerSpy).deleteWebhookAsAdmin(webhookId);
	}

	@Test
	public void testGenerateAndSendWebhookVerificationWithWebhookId() {
		doReturn(webhook).when(webhookManagerSpy).getWebhook(any(), any());
		doNothing().when(webhookManagerSpy).generateAndSendWebhookVerification(any());

		// Call under test
		webhookManagerSpy.generateAndSendWebhookVerification(userInfo, webhookId);

		verify(webhookManagerSpy).getWebhook(userInfo, webhookId);
		verify(webhookManagerSpy).generateAndSendWebhookVerification(webhook);
	}

	@Test
	public void testGenerateAndSendWebhookVerificationWithWebhook() {
		when(webhookManagerSpy.generateVerificationCode()).thenReturn(validVerificationCode);
		WebhookVerification toCreate = new WebhookVerification().setWebhookId(webhookId)
				.setVerificationCode(webhookManagerSpy.generateVerificationCode())
				.setExpiresOn(new Date(currentDate.getTime() + WebhookManagerImpl.VERIFICATION_CODE_TTL))
				.setAttempts(0L).setCreatedBy(userIdAsString).setCreatedOn(currentDate);

		when(mockWebhookVerificationDao.createWebhookVerification(any())).thenReturn(toCreate);

		// Call under test
		webhookManagerSpy.generateAndSendWebhookVerification(webhook);

		verify(mockWebhookVerificationDao).createWebhookVerification(toCreate);
	}

	@Test
	public void testValidateUserIsAdminOrWebhookOwnerWithOwner() {
		// Call under test
		webhookManagerSpy.validateUserIsAdminOrWebhookOwner(userInfo, webhook);
	}

	@Test
	public void testValidateUserIsAdminOrWebhookOwnerWithAdmin() {
		// Call under test
		webhookManagerSpy.validateUserIsAdminOrWebhookOwner(adminUserInfo, webhook);
	}

	@Test
	public void testValidateUserIsAdminOrWebhookOwnerWithAnonymous() {
		String errorMessage = assertThrows(UnauthorizedException.class, () -> {
			// Call under test
			webhookManagerSpy.validateUserIsAdminOrWebhookOwner(anonymousUserInfo, webhook);
		}).getMessage();

		assertEquals(WebhookManagerImpl.UNAUTHORIZED_ACCESS_MESSAGE, errorMessage);
	}

	@Test
	public void testValidateUserIsAdminOrWebhookOwnerWithUnauthorizedOwner() {
		String errorMessage = assertThrows(UnauthorizedException.class, () -> {
			// Call under test
			webhookManagerSpy.validateUserIsAdminOrWebhookOwner(unauthorizedUserInfo, webhook);
		}).getMessage();

		assertEquals(WebhookManagerImpl.UNAUTHORIZED_ACCESS_MESSAGE, errorMessage);
	}

	@Test
	public void testValidateUserIsAdminOrWebhookOwnerWithNullUserInfo() {
		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.validateUserIsAdminOrWebhookOwner(null, webhook);
		}).getMessage();

		assertEquals("userInfo is required.", errorMessage);
	}

	@Test
	public void testValidateUserIsAdminOrWebhookOwnerWithNullWebhook() {
		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.validateUserIsAdminOrWebhookOwner(userInfo, null);
		}).getMessage();

		assertEquals("webhook is required.", errorMessage);
	}

	@Test
	public void testValidateCreateOrUpdateArguments() {
		Webhook request = new Webhook().setObjectId(anotherObjectId).setObjectType(webhookObjectType)
				.setInvokeEndpoint(anotherValidApiGatewayEndpoint);

		// Call under test
		webhookManagerSpy.validateCreateOrUpdateArguments(userInfo, request);

		verify(webhookManagerSpy).validateInvokeEndpoint(anotherValidApiGatewayEndpoint);
	}

	@Test
	public void testValidateCreateOrUpdateArgumentsWithNullUserInfo() {
		Webhook request = new Webhook().setObjectId(anotherObjectId).setObjectType(webhookObjectType)
				.setInvokeEndpoint(anotherValidApiGatewayEndpoint);

		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.validateCreateOrUpdateArguments(null, request);
		}).getMessage();

		assertEquals("userInfo is required.", errorMessage);
	}

	@Test
	public void testValidateCreateOrUpdateArgumentsWithNullRequest() {
		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.validateCreateOrUpdateArguments(userInfo, null);
		}).getMessage();

		assertEquals("webhook is required.", errorMessage);
	}

	@Test
	public void testValidateCreateOrUpdateArgumentsWithNullObjectId() {
		Webhook request = new Webhook().setObjectId(null).setObjectType(webhookObjectType)
				.setInvokeEndpoint(anotherValidApiGatewayEndpoint);

		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.validateCreateOrUpdateArguments(userInfo, request);
		}).getMessage();

		assertEquals("webhook.objectId is required.", errorMessage);
	}

	@Test
	public void testValidateCreateOrUpdateArgumentsWithNullObjectType() {
		Webhook request = new Webhook().setObjectId(anotherObjectId).setObjectType(null)
				.setInvokeEndpoint(anotherValidApiGatewayEndpoint);

		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.validateCreateOrUpdateArguments(userInfo, request);
		}).getMessage();

		assertEquals("webhook.objectType is required.", errorMessage);
	}

	@Test
	public void testValidateCreateOrUpdateArgumentsWithNullObjectInvokeEndpoint() {
		Webhook request = new Webhook().setObjectId(anotherObjectId).setObjectType(webhookObjectType)
				.setInvokeEndpoint(null);

		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			webhookManagerSpy.validateCreateOrUpdateArguments(userInfo, request);
		}).getMessage();

		assertEquals("webhook.invokeEndpoint is required.", errorMessage);
	}

	@Test
	public void testValidateCreateOrUpdateArgumentsWithAnonymous() {
		Webhook request = new Webhook().setObjectId(anotherObjectId).setObjectType(webhookObjectType)
				.setInvokeEndpoint(anotherValidApiGatewayEndpoint);

		String errorMessage = assertThrows(UnauthorizedException.class, () -> {
			// Call under test
			webhookManagerSpy.validateCreateOrUpdateArguments(anonymousUserInfo, request);
		}).getMessage();

		assertEquals("Must login to perform this action", errorMessage);
	}

	@Test
	public void testValidateInvokeEndpointWithValidDomain() {
		List<String> validAwsApiGatewayEndpoints = List.of("https://abcd1234.execute-api.us-east-1.amazonaws.com",
				"https://my-api.execute-api.eu-west-1.amazonaws.com/path/to/resource",
				"https://service.execute-api.ap-southeast-2.amazonaws.com/dev",
				"https://test123.execute-api.us-west-2.amazonaws.com/prod",
				"https://api.execute-api.ca-central-1.amazonaws.com/v1/resource",
				"https://endpoint.execute-api.sa-east-1.amazonaws.com/stage",
				"https://webhook.execute-api.eu-north-1.amazonaws.com/api",
				"https://service123.execute-api.af-south-1.amazonaws.com",
				"https://test-service.execute-api.ap-northeast-1.amazonaws.com/path/to/resource",
				"https://api-service.execute-api.me-south-1.amazonaws.com");

		List<String> testAllValidAwsRegions = Stream.of(Regions.values()).map(Regions::getName)
				.map(region -> String.format("https://abcd1234.execute-api.%s.amazonaws.com/prod", region))
				.collect(Collectors.toList());

		List<String> validEndpoints = new ArrayList<String>();
		validEndpoints.addAll(validAwsApiGatewayEndpoints);
		validEndpoints.addAll(testAllValidAwsRegions);

		for (String validEndpoint : validEndpoints) {
			// Call under test
			webhookManagerSpy.validateInvokeEndpoint(validEndpoint);
		}
	}

	@Test
	public void testValidateInvokeEndpointWithInvalidDomain() {
		List<String> invalidAwsApiGatewayEndpoints = List.of("https://synapse.org",
				"http://abcd1234.execute-api.us-east-1.amazonaws.com",
				"https://my-api.execute-api.invalid-region.amazonaws.com/path/to/resource",
				"https://service.execute-api.eu-west-1.fakeaws.com/dev",
				"https://service.execute-api-.us-west-2.amazonaws.com/prod",
				"https://api.execute.ap.ca-central-1.amazonaws.com/v1/resource",
				"https://webhook.execute-api.us-west-1.amazon.com/api",
				"https://service123.execute-api.eu-central-1.amazonaws.net",
				"https://test-service.execute-api.us-iso-west-1.amazonaws.com:8080/path",
				"https://api-service.execute-api.amazonaws.com/path/to/resource",
				"https://api-service.us-east-1.amazonaws.com/execute-api/path");

		List<String> invalidEndpoints = new ArrayList<String>();
		invalidEndpoints.addAll(invalidAwsApiGatewayEndpoints);

		for (String invalidEndpoint : invalidEndpoints) {
			String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
				// Call under test
				webhookManagerSpy.validateInvokeEndpoint(invalidEndpoint);
			}).getMessage();

			assertEquals(String.format(WebhookManagerImpl.INVALID_INVOKE_ENDPOINT_MESSAGE, invalidEndpoint),
					errorMessage);
		}
	}

	@Test
	public void testGenerateVerificationCode() {
		String one = webhookManagerSpy.generateVerificationCode();
		String two = webhookManagerSpy.generateVerificationCode();

		// validate length
		assertEquals(WebhookManagerImpl.VERIFICATION_CODE_LENGTH, one.length());
		assertEquals(WebhookManagerImpl.VERIFICATION_CODE_LENGTH, two.length());

		// validate characters
		for (int i = 0; i < WebhookManagerImpl.VERIFICATION_CODE_LENGTH; i++) {
			assertTrue(WebhookManagerImpl.VERIFICATION_CODE_CHARACTERS.indexOf(one.charAt(i)) >= 0);
			assertTrue(WebhookManagerImpl.VERIFICATION_CODE_CHARACTERS.indexOf(two.charAt(i)) >= 0);
		}

		// validate redundancy
		assertNotEquals(one, two);
	}

	private List<UserInfo> createUsers(int numberOfUsers) {
		List<UserInfo> users = new ArrayList<>();
		for (long i = 0; i < numberOfUsers; i++) {
			UserInfo userInfo = new UserInfo(false, i);
			when(mockUserManager.getUserInfo(userInfo.getId())).thenReturn(userInfo);
			users.add(userInfo);
		}
		return users;
	}

	private List<Webhook> createDefaultWebhooksForUsers(List<UserInfo> users, int numberOfWebhooksPerUser) {
		return createWebhooksForUsers(users, numberOfWebhooksPerUser, objectId, objectType, true);
	}

	private List<Webhook> createWebhooksForUsers(List<UserInfo> users, int numberOfWebhooksPerUser, String objectId,
			ObjectType objectType, boolean hasReadPermission) {
		List<Webhook> webhooks = new ArrayList<>();
		for (UserInfo userInfo : users) {
			for (int i = 0; i < numberOfWebhooksPerUser; i++) {
				Webhook webhook = new Webhook().setWebhookId(String.format("webhookId%s-user%s", i, userInfo.getId()))
						.setUserId(String.valueOf(userInfo.getId())).setObjectId(objectId)
						.setObjectType(webhookObjectType).setIsWebhookEnabled(true).setIsVerified(true);

				if (hasReadPermission) {
					when(mockAclDao.canAccess(userInfo, objectId, objectType, ACCESS_TYPE.READ))
							.thenReturn(AuthorizationStatus.authorized());
				} else {
					String accessDeniedMessage = String.format("You do not have %s permission for %s : %s",
							ACCESS_TYPE.READ, webhook.getObjectType(), webhook.getObjectId());
					when(mockAclDao.canAccess(userInfo, objectId, objectType, ACCESS_TYPE.READ))
							.thenReturn(AuthorizationStatus.accessDenied(accessDeniedMessage));
				}

				webhooks.add(webhook);
			}
		}
		return webhooks;
	}

}