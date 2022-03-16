SELECT 
 N.ID AS ID,
 SUM(CRC32(CONCAT(':salt','-',N.ETAG,'-',R.NUMBER,'-',getEntityBenefactorId(N.ID)))) AS CHECK_SUM
  FROM JDONODE N JOIN JDOREVISION R ON (N.ID = R.OWNER_NODE_ID)
   WHERE N.ID IN (:objectIds) AND :trashId <> getEntityBenefactorId(N.ID)
   GROUP BY N.ID
    ORDER BY N.ID ASC
     LIMIT :limit OFFSET :offset