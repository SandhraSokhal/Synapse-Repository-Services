package org.sagebionetworks.table.cluster.description;

import static org.sagebionetworks.repo.model.table.TableConstants.ROW_BENEFACTOR;
import static org.sagebionetworks.repo.model.table.TableConstants.ROW_ETAG;
import static org.sagebionetworks.repo.model.table.TableConstants.ROW_ID;
import static org.sagebionetworks.repo.model.table.TableConstants.ROW_VERSION;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.sagebionetworks.repo.model.entity.IdAndVersion;
import org.sagebionetworks.repo.model.table.TableConstants;
import org.sagebionetworks.table.cluster.SQLUtils;
import org.sagebionetworks.table.cluster.SQLUtils.TableType;

public class ViewIndexDescription implements IndexDescription {

	private final IdAndVersion idAndVersion;
	
	public ViewIndexDescription(IdAndVersion idAndVersion) {
		super();
		this.idAndVersion = idAndVersion;
	}

	@Override
	public IdAndVersion getIdAndVersion() {
		return idAndVersion;
	}

	@Override
	public String getCreateOrUpdateIndexSql() {
		StringBuilder builder = new StringBuilder();
		builder.append("CREATE TABLE IF NOT EXISTS ");
		builder.append(SQLUtils.getTableNameForId(idAndVersion, TableType.INDEX));
		builder.append("( ");
		builder.append(ROW_ID).append(" BIGINT NOT NULL, ");
		builder.append(ROW_VERSION).append(" BIGINT NOT NULL, ");
		builder.append(ROW_ETAG).append(" varchar(36) NOT NULL, ");
		builder.append(ROW_BENEFACTOR).append(" BIGINT NOT NULL, ");
		builder.append("PRIMARY KEY (").append("ROW_ID").append(")");
		builder.append(", KEY `IDX_ETAG` (").append(ROW_ETAG).append(")");
		builder.append(", KEY `IDX_BENEFACTOR` (").append(ROW_BENEFACTOR).append(")");
		builder.append(")");
		return builder.toString();
	}

	@Override
	public List<String> getBenefactorColumnNames() {
		return Collections.singletonList(TableConstants.ROW_BENEFACTOR);
	}

	@Override
	public int hashCode() {
		return Objects.hash(idAndVersion);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof ViewIndexDescription)) {
			return false;
		}
		ViewIndexDescription other = (ViewIndexDescription) obj;
		return Objects.equals(idAndVersion, other.idAndVersion);
	}

	
}
