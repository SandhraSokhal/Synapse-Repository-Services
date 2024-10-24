package org.sagebionetworks.repo.model.dbo.dao.dataaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessRequirementDAO;
import org.sagebionetworks.repo.model.ManagedACTAccessRequirement;
import org.sagebionetworks.repo.model.Node;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.RestrictableObjectDescriptor;
import org.sagebionetworks.repo.model.UserGroup;
import org.sagebionetworks.repo.model.UserGroupDAO;
import org.sagebionetworks.repo.model.dataaccess.AccessType;
import org.sagebionetworks.repo.model.dataaccess.AccessorChange;
import org.sagebionetworks.repo.model.dataaccess.Request;
import org.sagebionetworks.repo.model.dataaccess.RequestInterface;
import org.sagebionetworks.repo.model.dataaccess.ResearchProject;
import org.sagebionetworks.repo.model.dbo.dao.AccessRequirementUtilsTest;
import org.sagebionetworks.repo.model.jdo.NodeTestUtils;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class DBORequestDAOImplTest {

	@Autowired
	private UserGroupDAO userGroupDAO;

	@Autowired
	private NodeDAO nodeDao;

	@Autowired
	private AccessRequirementDAO accessRequirementDAO;

	@Autowired
	private ResearchProjectDAO researchProjectDao;

	@Autowired
	private RequestDAO requestDao;

	@Autowired
	private TransactionTemplate readCommitedTransactionTemplate;

	private UserGroup individualGroup = null;
	private Node node = null;
	private ManagedACTAccessRequirement accessRequirement = null;
	private ResearchProject researchProject = null;
	private String toDelete;

	@BeforeEach
	public void before() {
		toDelete = null;

		// create a user
		individualGroup = new UserGroup();
		individualGroup.setIsIndividual(true);
		individualGroup.setCreationDate(new Date());
		individualGroup.setId(userGroupDAO.create(individualGroup).toString());

		// create a node
		node = NodeTestUtils.createNew("foo", Long.parseLong(individualGroup.getId()));
		node.setId(nodeDao.createNew(node));

		// create an ACTAccessRequirement
		accessRequirement = new ManagedACTAccessRequirement();
		accessRequirement.setCreatedBy(individualGroup.getId());
		accessRequirement.setCreatedOn(new Date());
		accessRequirement.setModifiedBy(individualGroup.getId());
		accessRequirement.setModifiedOn(new Date());
		accessRequirement.setEtag("10");
		accessRequirement.setAccessType(ACCESS_TYPE.DOWNLOAD);
		RestrictableObjectDescriptor rod = AccessRequirementUtilsTest.createRestrictableObjectDescriptor(node.getId());
		accessRequirement.setSubjectIds(Arrays.asList(new RestrictableObjectDescriptor[]{rod, rod}));
		accessRequirement = accessRequirementDAO.create(accessRequirement);

		// create a ResearchProject
		researchProject = ResearchProjectTestUtils.createNewDto();
		researchProject.setAccessRequirementId(accessRequirement.getId().toString());
		researchProject = researchProjectDao.create(researchProject);
	}

	@AfterEach
	public void after() {
		if (toDelete != null) {
			requestDao.delete(toDelete);
		}
		if (researchProject != null) {
			researchProjectDao.delete(researchProject.getId());
		}
		if (accessRequirement != null) {
			accessRequirementDAO.delete(accessRequirement.getId().toString());
		}
		if (node != null) {
			nodeDao.delete(node.getId());
			node = null;
		}
		if (individualGroup != null) {
			userGroupDAO.delete(individualGroup.getId());
		}
	}

	@Test
	public void testNotFound() {
		Request dto = RequestTestUtils.createNewRequest();
		String message = assertThrows(NotFoundException.class, () -> {			
			requestDao.getUserOwnCurrentRequest(dto.getAccessRequirementId(), dto.getCreatedBy());
		}).getMessage();
		
		assertEquals("Data access request does not exist for access requirement: '" + dto.getAccessRequirementId() + "' and user id: '"+ dto.getCreatedBy() +"'", message);
	}

	@Test
	public void testCRUD() {
		Request dto = RequestTestUtils.createNewRequest();
		dto.setAccessRequirementId(accessRequirement.getId().toString());
		dto.setResearchProjectId(researchProject.getId());
		Request created = requestDao.create(dto);
		dto.setId(created.getId());
		dto.setEtag(created.getEtag());
		assertEquals(dto, created);

		// should get back the same object
		assertEquals(dto, (Request) requestDao.getUserOwnCurrentRequest(
				dto.getAccessRequirementId(), dto.getCreatedBy()));
		assertEquals(dto, (Request) requestDao.get(dto.getId()));
		toDelete = dto.getId();
		
		AccessorChange add = new AccessorChange();
		add.setUserId("666");
		add.setType(AccessType.GAIN_ACCESS);

		// update
		dto.setAccessorChanges(Arrays.asList(add));
		final RequestInterface updated = requestDao.update(dto);
		dto.setEtag(updated.getEtag());
		assertEquals(dto, updated);

		// insert another one with the same accessRequirementId & createdBy
		assertThrows(IllegalArgumentException.class, () -> {			
			requestDao.create(dto);
		});

		// test get for update
		Request locked = readCommitedTransactionTemplate.execute(new TransactionCallback<Request>() {
			@Override
			public Request doInTransaction(TransactionStatus status) {
				return (Request) requestDao.getForUpdate(updated.getId());
			}
		});
		assertEquals(updated, locked);
	}

	@Test
	public void testGetForUpdateWithoutTransaction() {
		Request dto = RequestTestUtils.createNewRequest();
		
		assertThrows(IllegalTransactionStateException.class, () -> {			
			requestDao.getForUpdate(dto.getId());
		});
	}
	
	@Test
	public void testGetAccessRequirementId() {
		Request request = RequestTestUtils.createNewRequest();
		request.setAccessRequirementId(accessRequirement.getId().toString());
		request.setResearchProjectId(researchProject.getId());
		request = requestDao.create(request);
		
		toDelete = request.getId();
		
		// Call under test
		String result = requestDao.getAccessRequirementId(request.getId());
		
		assertEquals(accessRequirement.getId().toString(), result);
	}
	
	@Test
	public void testGetAccessRequirementIdWithNonExisting() {
		
		String message = assertThrows(NotFoundException.class, () -> {			
			// Call under test
			requestDao.getAccessRequirementId("-123");
		}).getMessage();
		
		assertEquals("Data access request: '-123' does not exist", message);
	}
}
