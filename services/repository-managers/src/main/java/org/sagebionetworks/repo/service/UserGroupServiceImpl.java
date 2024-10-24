package org.sagebionetworks.repo.service;

import java.util.List;

import org.sagebionetworks.reflection.model.PaginatedResults;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserGroup;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserGroupServiceImpl implements UserGroupService {
	
	@Autowired
	UserManager userManager;
	
	@Override
	public PaginatedResults<UserGroup> getUserGroups(
			Long userId, Integer offset, Integer limit, String sort, Boolean ascending) 
			throws DatastoreException, UnauthorizedException, NotFoundException {
		UserInfo userInfo = userManager.getUserInfo(userId);
		long endExcl = offset+limit;
		List<UserGroup> results = userManager.getGroupsInRange(userInfo, offset, endExcl, sort, ascending);
		int totalNumberOfResults = userManager.getGroups().size();
		return PaginatedResults.createWithLimitAndOffset(results, (long)limit, (long)offset);
	}
}
