package evolvia.save;

/** A save game cannot be written or read; the message is meant for the player. */
public final class SaveException extends RuntimeException {

    public SaveException(String message) {
        super(message);
    }

    public SaveException(String message, Throwable cause) {
        super(message, cause);
    }
}
