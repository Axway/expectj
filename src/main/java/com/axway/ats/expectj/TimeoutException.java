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
 * Timeout while waiting for a {@link Spawn}.
 * @author johan.walles@gmail.com
 */
public class TimeoutException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Create a new exception with an explanatory message.
     * @param message An explanation of what went wrong.
     */
    TimeoutException( String message ) {

        super( message );
    }

    /**
     * Create a new exception with an explanatory message and a reference to an exception
     * that made us throw this one.
     * @param message An explanation of what went wrong.
     * @param cause Another exception that is the reason to throw this one.
     */
    TimeoutException( String message,
                      Throwable cause ) {

        super( message, cause );
    }
}
