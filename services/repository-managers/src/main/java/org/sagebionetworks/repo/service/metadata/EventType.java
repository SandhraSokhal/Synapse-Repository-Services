package org.sagebionetworks.repo.service.metadata;

/**
 * The event type that triggered this call.
 * @author jmhill
 *
 */
public enum EventType{
	CREATE,
	UPDATE,
	GET,
	DELETE,
	NEW_VERSION
}