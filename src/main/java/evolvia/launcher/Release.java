package evolvia.launcher;

import java.net.URI;
import java.util.List;

/**
 * A published release: its tag (e.g. {@code v0.2}) and downloadable packages.
 */
public record Release(String tag, List<Asset> assets) {

    /** One downloadable file of a release. */
    public record Asset(String name, URI url, long size) {
    }
}
