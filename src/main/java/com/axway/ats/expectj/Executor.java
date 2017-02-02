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

import java.io.IOException;

/**
 * This interface exists for people who want control over how processes are
 * launched.
 * <p>
 * Implementors are encouraged to implement {@link #toString()} for logging purposes.
 *
 * @see ExpectJ#spawn(String)
 * @author Johan Walles, johan.walles@gmail.com
 */
public interface Executor {
    /**
     * Creates a new process. This will only be called once.
     * @return The new process.
     * @throws IOException if there's a problem starting the new process.
     * @see #toString()
     */
    Process execute() throws IOException;

    /**
     * Describes what {@link #execute()} created.
     * @return A short description of what {@link #execute()} returns.
     */
    public String toString();
}
