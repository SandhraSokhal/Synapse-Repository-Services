package org.sagebionetworks.table.cluster.stats;

import java.util.Optional;

import org.sagebionetworks.table.cluster.TableAndColumnMapper;
import org.sagebionetworks.table.query.model.ColumnReference;

public class ColumnReferenceGenerator implements StatGeneratorInteface<ColumnReference> {

	@Override
	public Optional<ElementStats> generate(ColumnReference element, TableAndColumnMapper tableAndColumnMapper) {
		return tableAndColumnMapper.lookupColumnReference(element)
				.map(columnTranslationReference -> ElementStats.builder()
					.setMaximumSize(columnTranslationReference.getMaximumSize())
					.setMaxListLength(columnTranslationReference.getMaximumListLength())
					.setDefaultValue(columnTranslationReference.getDefaultValues())
					.setFacetType(columnTranslationReference.getFacetType())
					.setJsonSubColumns(columnTranslationReference.getJsonSubColumns())
					.build());
	}
}
