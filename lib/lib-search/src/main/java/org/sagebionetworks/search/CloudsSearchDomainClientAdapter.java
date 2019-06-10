package org.sagebionetworks.search;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.amazonaws.services.cloudsearchdomain.model.UploadDocumentsResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.kinesis.AwsKinesisFirehoseLogger;
import org.sagebionetworks.kinesis.AwsKinesisLogRecord;
import org.sagebionetworks.repo.model.search.Document;
import org.sagebionetworks.repo.web.TemporarilyUnavailableException;
import org.sagebionetworks.util.Clock;
import org.sagebionetworks.util.ThreadLocalProvider;
import org.sagebionetworks.util.ValidateArgument;

import com.amazonaws.services.cloudsearchdomain.AmazonCloudSearchDomain;
import com.amazonaws.services.cloudsearchdomain.model.AmazonCloudSearchDomainException;
import com.amazonaws.services.cloudsearchdomain.model.DocumentServiceException;
import com.amazonaws.services.cloudsearchdomain.model.SearchException;
import com.amazonaws.services.cloudsearchdomain.model.SearchRequest;
import com.amazonaws.services.cloudsearchdomain.model.SearchResult;
import com.amazonaws.services.cloudsearchdomain.model.UploadDocumentsRequest;
import com.google.common.collect.Iterators;

/**
 * A wrapper for AWS's AmazonCloudSearchDomain. DO NOT INSTANTIATE.
 * Use CloudSearchClientProvider to get an instance of this class.
 */
public class CloudsSearchDomainClientAdapter {
	static private Logger logger = LogManager.getLogger(CloudsSearchDomainClientAdapter.class);

	static ThreadLocal<List<CloudSearchDocumentGenerationAwsKinesisLogRecord>> threadLocalRecordList =
			ThreadLocalProvider.getInstanceWithInitial(CloudSearchDocumentGenerationAwsKinesisLogRecord.KINESIS_DATA_STREAM_NAME_SUFFIX, ArrayList::new);


	private final AmazonCloudSearchDomain client;
	private final CloudSearchDocumentBatchIteratorProvider iteratorProvider;
	private final Clock clock;

	//used for logging ifo about a batch of documents
	private final AwsKinesisFirehoseLogger firehoseLogger;

	CloudsSearchDomainClientAdapter(AmazonCloudSearchDomain client, CloudSearchDocumentBatchIteratorProvider iteratorProvider, AwsKinesisFirehoseLogger firehoseLogger, Clock clock){
		this.client = client;
		this.iteratorProvider = iteratorProvider;
		this.firehoseLogger = firehoseLogger;
		this.clock = clock;
	}

	public void sendDocuments(Iterator<Document> documents){
		ValidateArgument.required(documents, "documents");
		Iterator<CloudSearchDocumentBatch> searchDocumentFileIterator = iteratorProvider.getIterator(documents);


		while(searchDocumentFileIterator.hasNext()) {
			Set<String> documentIds = null;
			try (CloudSearchDocumentBatch batch = searchDocumentFileIterator.next();
				 InputStream fileStream = batch.getNewInputStream();) {

				documentIds = batch.getDocumentIds();

				UploadDocumentsRequest request = new UploadDocumentsRequest()
						.withContentType("application/json")
						.withDocuments(fileStream)
						.withContentLength(batch.size());
				UploadDocumentsResult result = client.uploadDocuments(request);
				updateAndSendKinesisLogRecords(result.getStatus());

				//dont upload large batches too frequently since CloudSearch may not be able to handle it fast enough.
				if(documentIds.size() > 1) {
					// PLFM-5570 and https://docs.aws.amazon.com/cloudsearch/latest/developerguide/limits.html
					// limit on 1 batch per 10 seconds
					clock.sleep(10000);
				}
			} catch (DocumentServiceException e) {
				logger.error("The following documents failed to upload: " +  documentIds);
				documentIds = null;
				updateAndSendKinesisLogRecords(e.getStatus());
				throw handleCloudSearchExceptions(e);
			} catch (IOException e){
				throw new TemporarilyUnavailableException(e);
			} catch (InterruptedException e) {
				logger.warn("sleep was interrupted. exiting.");
				return;
			} finally{
				//remove all records from the threadlocal list
				threadLocalRecordList.get().clear();
			}
		}
	}

	void updateAndSendKinesisLogRecords(final String status){
		final long batchUploadTimestamp = System.currentTimeMillis();
		final String batchUUID = UUID.randomUUID().toString();

		//exit early if map is empty since kinesis does not allow pushing of empty messages
		if(threadLocalRecordList.get().isEmpty()){
			logger.warn("threadLocalMap was null");
			return;
		}

		//add additional batch releated metadata to each record in the batch and push to kinesis
		Stream<AwsKinesisLogRecord> logRecordStream = threadLocalRecordList.get().stream()
				.map((record) ->
					record.withDocumentBatchUpdateStatus(status)
							.withDocumentBatchUpdateTimestamp(batchUploadTimestamp)
							.withDocumentBatchUUID(batchUUID)
				);
		firehoseLogger.logBatch(CloudSearchDocumentGenerationAwsKinesisLogRecord.KINESIS_DATA_STREAM_NAME_SUFFIX, logRecordStream);
	}


	public void sendDocument(Document document){
		ValidateArgument.required(document, "document");
		sendDocuments(Iterators.singletonIterator(document));
	}

	SearchResult rawSearch(SearchRequest request) {
		ValidateArgument.required(request, "request");

		try{
			return client.search(request);
		}catch (SearchException e){
			throw handleCloudSearchExceptions(e);
		}
	}

	RuntimeException handleCloudSearchExceptions(AmazonCloudSearchDomainException e){
		int statusCode = e.getStatusCode();
		if(statusCode / 100 == 4){ //4xx status codes
			return new IllegalArgumentException(e);
		} else if (statusCode / 100 == 5){ // 5xx status codes
			// The AWS API already has retry logic for 5xx status codes so getting one here means retries failed
			// AmazonCloudSearchDomainException is a subclass of AmazonServiceException,
			// which is already handled by BaseController and mapped to a 502 HTTP error
			return e;
		}else {
			logger.warn("Failed for unexpected reasons with status: " + e.getStatusCode());
			return e;
		}
	}

}
