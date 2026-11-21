package tel.eden.mod.net;

import java.util.List;

/** Parsed {@code wikiReply} payload returned by the backend for {@code /eden wiki}. */
public record WikiReplyData(long requestId, String status, String query, String title, String url, String lore, String description, String obtaining, String location, List<WikiField> fields, List<WikiChoice> choices, String error) {
	public WikiReplyData {
		status = status == null ? "" : status;
		query = query == null ? "" : query;
		title = title == null ? "" : title;
		url = url == null ? "" : url;
		lore = lore == null ? "" : lore;
		description = description == null ? "" : description;
		obtaining = obtaining == null ? "" : obtaining;
		location = location == null ? "" : location;
		fields = fields == null ? List.of() : List.copyOf(fields);
		choices = choices == null ? List.of() : List.copyOf(choices);
		error = error == null ? "" : error;
	}

	/** One infobox key/value pair included on a {@code "page"} reply. */
	public record WikiField(String name, String value) {
		public WikiField {
			name = name == null ? "" : name;
			value = value == null ? "" : value;
		}
	}

	/** One search hit returned on a {@code "choices"} disambiguation reply. */
	public record WikiChoice(String title, String url, String snippet) {
		public WikiChoice {
			title = title == null ? "" : title;
			url = url == null ? "" : url;
			snippet = snippet == null ? "" : snippet;
		}
	}
}
