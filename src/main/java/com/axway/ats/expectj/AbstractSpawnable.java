/*
 * Copyright 2017 Axway Software
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.axway.ats.expectj;

/**
 * Base class for spawnables providing an {@link #onClose()} method that should
 * be called on close.
 *
 * @author johan.walles@gmail.com
 */
public abstract class AbstractSpawnable implements Spawnable {
    /**
     * If non-null, will be notified {@link #onClose()}.
     */
    private CloseListener closeListener;

    public void setCloseListener(
                                  CloseListener closeListener ) {

        synchronized( this ) {
            this.closeListener = closeListener;
        }
    }

    /**
     * Call the close listener if we have one.
     */
    protected final void onClose() {

        synchronized( this ) {
            if( closeListener != null ) {
                closeListener.onClose();
            }
        }
    }
}
