package org.sagebionetworks.logging.s3;

import java.io.File;

import org.springframework.beans.factory.annotation.Autowired;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3ObjectSummary;

public class LogDAOImpl implements LogDAO {
	
	@Autowired
	private AmazonS3Client s3Client;
	private int stackInstanceNumber;
	private String stackInstancePrefixString;
	private String bucketName;
	
	/**
	 * Injected via Spring
	 * @param instanceNumber
	 */
	public void setStackInstanceNumber(int stackInstanceNumber) {
		this.stackInstanceNumber = stackInstanceNumber;
		this.stackInstancePrefixString = LogKeyUtils.getInstancePrefix(stackInstanceNumber);
	}

	/**
	 * Injected via Spring
	 * @param bucketName
	 */
	public void setBucketName(String bucketName) {
		this.bucketName = bucketName;
	}

	@Override
	public String saveLogFile(File toSave, long timestamp) {
		// Create the key for the new log file.
		String key = LogKeyUtils.createKeyForFile(this.stackInstanceNumber,
				toSave.getName(), timestamp);
		ObjectMetadata om = new ObjectMetadata();
		om.setContentType("application/x-gzip");
		om.setContentEncoding("gzip");
		om.setContentDisposition("attachment; filename=" + key + ";");
		s3Client.putObject(new PutObjectRequest(bucketName, key, toSave)
				.withMetadata(om));
		return key;
	}

	@Override
	public void deleteLogFile(String key) {
		// Delete a log file by its key
		s3Client.deleteObject(bucketName, key);
	}

	/**
	 * Called when the bean is initialized.
	 */
	public void initialize() {
		if (bucketName == null)
			throw new IllegalArgumentException("bucketName has not been set and cannot be null");
		// Create the bucket if it does not exist
		s3Client.createBucket(bucketName);
	}
	
	@Override
	public void deleteAllStackInstanceLogs() {
		// List all object with the prefix
		boolean done = false;
		while(!done){
			ObjectListing listing = s3Client.listObjects(bucketName, this.stackInstancePrefixString);
			done = !listing.isTruncated();
			// Delete all
			if(listing.getObjectSummaries() != null){
				for(S3ObjectSummary summary: listing.getObjectSummaries()){
					s3Client.deleteObject(bucketName, summary.getKey());
				}
			}
		}
	}
}
