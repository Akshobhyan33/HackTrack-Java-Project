package hacktrack.ai;

import java.util.List;

/**
 * A single fetched page after HTML cleanup.
 */
public class FetchedPage {

    private final String url;
    private final String title;
    private final String text;
    private final List<String> relevantLinks;
    private final String note;

    public FetchedPage(String url, String title, String text, List<String> relevantLinks, String note) {
        this.url = url;
        this.title = title;
        this.text = text;
        this.relevantLinks = relevantLinks;
        this.note = note;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    /** Cleaned, readable text with scripts, styles and navigation removed. */
    public String getText() {
        return text;
    }

    /** Same-host links whose anchor text or href looked schedule related. */
    public List<String> getRelevantLinks() {
        return relevantLinks;
    }

    /** Optional human readable note (for example: "skipped: non-HTML content"). */
    public String getNote() {
        return note;
    }
}
