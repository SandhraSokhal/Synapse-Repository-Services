WITH
	TOTAL AS (
		SELECT COUNT(*) AS TOTAL_FILE_COUNT FROM DOWNLOAD_LIST_ITEM_V2 D WHERE D.PRINCIPAL_ID = :principalId
    ),
    CUR_VER AS (
		SELECT D.*, R.FILE_HANDLE_ID, R.NUMBER AS ACTUAL_VERSION FROM DOWNLOAD_LIST_ITEM_V2 D
			JOIN NODE N ON (D.ENTITY_ID = N.ID) 
            JOIN NODE_REVISION R ON (N.ID = R.OWNER_NODE_ID AND N.CURRENT_REV_NUM = R.NUMBER)
				WHERE D.VERSION_NUMBER = -1 AND D.PRINCIPAL_ID = :principalId
    ),
    VER AS (
		SELECT D.*, R.FILE_HANDLE_ID, R.NUMBER AS ACTUAL_VERSION FROM DOWNLOAD_LIST_ITEM_V2 D
            JOIN NODE_REVISION R ON (D.ENTITY_ID = R.OWNER_NODE_ID AND D.VERSION_NUMBER = R.NUMBER)
				WHERE D.VERSION_NUMBER <> -1 AND D.PRINCIPAL_ID = :principalId
    ),
    VER_U AS (
		SELECT * FROM CUR_VER
        UNION ALL
        SELECT * FROM VER
    )
SELECT 
	COUNT(*) AS AVAILABLE_COUNT, 
	SUM(F.CONTENT_SIZE) AS SUM_AVAIABLE_SIZE,
    (SELECT TOTAL_FILE_COUNT FROM TOTAL) AS TOTAL_FILE_COUNT,
    COUNT( CASE WHEN F.METADATA_TYPE = 'S3' AND F.CONTENT_SIZE <= :maxEligibleSize THEN 1 ELSE NULL END) AS ELIGIBLE_FOR_PACKAGING_COUNT
	FROM %S T JOIN VER_U ON (T.ENTITY_ID = VER_U.ENTITY_ID) JOIN FILES F ON (VER_U.FILE_HANDLE_ID = F.ID)