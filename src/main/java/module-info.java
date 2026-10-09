module com.maiawb {
    requires javafx.controls;
    requires javafx.graphics;
    requires java.desktop;

    exports com.maiawb;
    exports com.maiawb.cli;
    exports com.maiawb.core;
    exports com.maiawb.core.cri;
    exports com.maiawb.core.io;
    exports com.awb.converter;

    opens com.maiawb to javafx.graphics;
}