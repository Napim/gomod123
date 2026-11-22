package net.weavemc.loader.api.event;

public class MouseEvent extends Event {
    private final int button;
    private final boolean buttonState;
    private final int x;
    private final int y;

    public MouseEvent(int button, boolean buttonState, int x, int y) {
        this.button = button;
        this.buttonState = buttonState;
        this.x = x;
        this.y = y;
    }

    public int getButton() {
        return button;
    }

    public boolean getButtonState() {
        return buttonState;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }
}
