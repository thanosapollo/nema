package org.thanosapollo.nema.ui.chat;

import androidx.navigationevent.NavigationEventDispatcher;
import androidx.navigationevent.NavigationEventDispatcherOwner;
import androidx.navigationevent.NavigationEventInput;

public final class TestNavigationEventOwner implements NavigationEventDispatcherOwner {
    private final NavigationEventDispatcher dispatcher = new NavigationEventDispatcher();
    private final BackInput input = new BackInput();

    public TestNavigationEventOwner() {
        dispatcher.addInput(input);
    }

    @Override
    public NavigationEventDispatcher getNavigationEventDispatcher() {
        return dispatcher;
    }

    public void completeBack() {
        input.completeBack();
    }

    private static final class BackInput extends NavigationEventInput {
        void completeBack() {
            dispatchOnBackCompleted();
        }
    }
}
