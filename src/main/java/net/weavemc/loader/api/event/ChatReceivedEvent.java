package net.weavemc.loader.api.event;

import net.minecraft.util.IChatComponent;

public class ChatReceivedEvent extends Event {
    private IChatComponent message;

    public ChatReceivedEvent(IChatComponent message) {
        this.message = message;
    }

    public IChatComponent getMessage() {
        return message;
    }

    public void setMessage(IChatComponent message) {
        this.message = message;
    }
}
