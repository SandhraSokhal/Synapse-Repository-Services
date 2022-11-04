SELECT 
S.ID AS ID,
 SUM(CRC32(CONCAT(:salt,'-',R.ETAG,'-',R.SUBSTATUS_VERSION,'-',S.EVALUATION_ID))) AS CHECK_SUM
	FROM EVALUATION_SUBMISSION S
	 INNER JOIN EVALUATION_SUBMISSION_STATUS R ON (S.ID = R.ID) 
	 WHERE S.EVALUATION_ID IN (:evaluationIds) GROUP BY S.ID ORDER BY S.ID ASC LIMIT :limit OFFSET :offset;