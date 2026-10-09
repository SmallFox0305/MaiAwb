module top.smallfox.maiacb {
    requires javafx.controls;
    requires javafx.graphics;
    requires java.desktop;

    exports top.smallfox.maiacb;
    exports top.smallfox.maiacb.cli;
    exports top.smallfox.maiacb.core;
    exports top.smallfox.maiacb.core.cri;
    exports top.smallfox.maiacb.core.io;
    exports top.smallfox.maiacb.converter;

    opens top.smallfox.maiacb to javafx.graphics;
}