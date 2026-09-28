package evolvia.components;

/** A tribe member's job (phase 9h): builder while there is a site, otherwise gatherer. */
public final class Role {

    public boolean builder;

    public Role(boolean builder) {
        this.builder = builder;
    }
}
