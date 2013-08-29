package org.sagebionetworks.repo.model.dbo.dao;

import java.io.IOException;
import java.util.Date;

import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.Favorite;
import org.sagebionetworks.repo.model.SchemaCache;
import org.sagebionetworks.repo.model.UserProfile;
import org.sagebionetworks.repo.model.dbo.persistence.DBOFavorite;
import org.sagebionetworks.repo.model.dbo.persistence.DBOUserProfile;
import org.sagebionetworks.repo.model.jdo.JDOSecondaryPropertyUtils;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.schema.ObjectSchema;

public class UserProfileUtils {
	
	public static void copyDtoToDbo(UserProfile dto, DBOUserProfile dbo) throws DatastoreException{
		if (dto.getOwnerId()==null) {
			dbo.setOwnerId(null);
		} else {
			dbo.setOwnerId(Long.parseLong(dto.getOwnerId()));
		}
		dbo.seteTag(dto.getEtag());
		try {
			dbo.setProperties(JDOSecondaryPropertyUtils.compressObject(dto));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static void copyDboToDto(DBOUserProfile dbo, UserProfile dto) throws DatastoreException {
		Object decompressed = null;
		try {
			decompressed = JDOSecondaryPropertyUtils.decompressedObject(dbo.getProperties());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		try {
			UserProfile copy = (UserProfile) decompressed;
			dto.setAgreesToTermsOfUse(copy.getAgreesToTermsOfUse());
			dto.setCompany(copy.getCompany());
			dto.setDisplayName(copy.getDisplayName());
			dto.setEmail(copy.getEmail());
			dto.setEtag(copy.getEtag());
			dto.setLocation(copy.getLocation());
			dto.setFirstName(copy.getFirstName());
			dto.setIndustry(copy.getIndustry());
			dto.setLastName(copy.getLastName());
			dto.setOwnerId(copy.getOwnerId());
			dto.setPic(copy.getPic());
			dto.setPosition(copy.getPosition());
			dto.setRStudioUrl(copy.getRStudioUrl());
			dto.setSummary(copy.getSummary());
			dto.setTeamName(copy.getTeamName());
			dto.setUri(copy.getUri());
			dto.setUrl(copy.getUrl());
			dto.setUserName(copy.getUserName());
		} catch (ClassCastException cce) {
			ObjectSchema schema = SchemaCache.getSchema(UserProfile.class);
			SchemaSerializationUtils.mapAnnotationsToDtoFields(dbo.getProperties(), dto, schema);
		}
		
		if (dbo.getOwnerId()==null) {
			dto.setOwnerId(null);
		} else {
			dto.setOwnerId(dbo.getOwnerId().toString());
		}
		dto.setEtag(dbo.geteTag());
	}
	
	
	/*
	 * Favorite methods
	 */
	public static String getFavoriteId(Favorite favorite) {
		if(favorite == null) return null;
		return getFavoriteId(favorite.getPrincipalId(), favorite.getEntityId());
	}
	
	public static String getFavoriteId(String principalId, String entityId) {
		if(principalId != null && entityId != null)
			return principalId + "-" + entityId;
		return null;		
	}
	
	public static String getFavoritePrincipalIdFromId(String id) {
		if(id != null) {
			String[] parts = id.split("-");
			if(parts != null && parts.length >= 1) {
				return parts[0];
			}
		}
		return null;
	}

	public static String getFavoriteEntityIdFromId(String id) {
		if(id != null) {
			String[] parts = id.split("-");
			if(parts != null && parts.length >= 2) {
				return parts[1];
			}
		}
		return null;
	}

	public static void copyDtoToDbo(Favorite dto, DBOFavorite dbo) throws DatastoreException {
		if(dto.getPrincipalId() == null) throw new IllegalArgumentException("principalId can not be null");
		if(dto.getEntityId() == null) throw new IllegalArgumentException("entityId can not be null");
		dbo.setPrincipalId(Long.parseLong(dto.getPrincipalId()));
		dbo.setNodeId(KeyFactory.stringToKey(dto.getEntityId()));		
		if(dto.getCreatedOn() != null)
			dbo.setCreatedOn(dto.getCreatedOn().getTime());		
	}

	public static Favorite copyDboToDto(DBOFavorite dbo) throws DatastoreException {
		if(dbo.getPrincipalId() == null) throw new IllegalArgumentException("principalId can not be null");
		if(dbo.getNodeId() == null) throw new IllegalArgumentException("nodeId can not be null");
		Favorite dto = new Favorite();
		dto.setPrincipalId(dbo.getPrincipalId().toString());
		dto.setEntityId(KeyFactory.keyToString(dbo.getNodeId()));
		dto.setCreatedOn(new Date(dbo.getCreatedOn()));
		return dto;
	}

}
