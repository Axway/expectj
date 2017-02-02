package com.axway.ats.expectj;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

/**
 * This class is used for talking to processes / ports. This will also interact
 * with the process to read and write to it.
 *
 * @author Sachin Shekar Shetty
 */
public class Spawn {
    /**
     * Log messages go here.
     */
    private final static Logger LOG                      = Logger.getLogger( Spawn.class );

    /** Default time out for expect commands */
    private long                m_lDefaultTimeOutSeconds = -1;

    /**
     * Buffered wrapper stream for slave's stdin.
     */
    private BufferedWriter      toStdin                  = null;

    /**
     * This is what we're actually talking to.
     */
    private SpawnableHelper     slave                    = null;

    /**
     * Turns false on timeout.
     */
    private volatile boolean    continueReading          = true;

    /**
     * Pumps data from stdin to the spawn's stdin.
     */
    private StreamPiper         interactIn               = null;

    /**
     * Pumps data from the spawn's stdout to stdout.
     */
    private StreamPiper         interactOut              = null;

    /**
     * Pumps data from the spawn's stderr to stderr.
     */
    private StreamPiper         interactErr              = null;

    /**
     * Wait for data from spawn's stdout.
     */
    private Selector            stdoutSelector;

    /**
     * Wait for data from spawn's stderr.
     */
    private Selector            stderrSelector;

    /**
     * This object will be notified on timer timeout or when the spawn we're
     * waiting for closes.
     */
    private final Object        doneWaitingForClose      = new Object();

    /**
     * This is the piece of text we currently work with
     */
    private StringBuilder       currentBuffer            = new StringBuilder();

    /**
     * This is the piece of text after the last match
     */
    private StringBuilder       pendingToMatchBuffer     = new StringBuilder();

    /**
     * Constructor
     *
     * @param spawn This is what we'll control.
     * @param lDefaultTimeOutSeconds Default timeout for expect commands
     * @throws IOException on trouble launching the spawn
     */
    Spawn( Spawnable spawn,
           long lDefaultTimeOutSeconds ) throws IOException {

        if( lDefaultTimeOutSeconds < -1 ) {
            throw new IllegalArgumentException( "Timeout must be >= -1, was " + lDefaultTimeOutSeconds );
        }
        m_lDefaultTimeOutSeconds = lDefaultTimeOutSeconds;

        slave = new SpawnableHelper( spawn, lDefaultTimeOutSeconds );
        slave.start();
        LOG.debug( "Spawned Process: " + spawn );

        if( slave.getStdin() != null ) {
            toStdin = new BufferedWriter( new OutputStreamWriter( slave.getStdin() ) );
        }

        stdoutSelector = Selector.open();
        slave.getStdoutChannel().register( stdoutSelector, SelectionKey.OP_READ );
        if( slave.getStderrChannel() != null ) {
            stderrSelector = Selector.open();
            slave.getStderrChannel().register( stderrSelector, SelectionKey.OP_READ );
        }
    }

    /**
     * This method is invoked by our {@link Timer} when the time-out occurs.
     */
    private synchronized void timerTimedOut() {

        continueReading = false;
        stdoutSelector.wakeup();
        if( stderrSelector != null ) {
            stderrSelector.wakeup();
        }
        synchronized( doneWaitingForClose ) {
            doneWaitingForClose.notify();
        }
    }

    /**
     * This method is invoked by our {@link Timer} when the timer thread
     * receives an interrupted exception
     */
    private void timerInterrupted() {

        timerTimedOut();
    }

    /**
     * @return the content after the last match
     */
    public String getPendingToMatchContent() {

        return this.pendingToMatchBuffer.toString();
    }

    /**
     * Wait for a pattern to appear on standard out.
     * @param pattern The case-insensitive substring to match against.
     * @param timeOutSeconds The timeout in seconds before the match fails.
     * @throws IOException on IO trouble waiting for pattern
     * @throws TimeoutException on timeout waiting for pattern
     */
    public void expect(
                        String pattern,
                        boolean isRegex,
                        long timeOutSeconds ) throws IOException, TimeoutException {

        expect( pattern, isRegex, timeOutSeconds, stdoutSelector );
    }

    /**
     * Wait for the spawned process to finish.
     * @param timeOutSeconds The number of seconds to wait before giving up, or
     * -1 to wait forever.
     * @throws ExpectJException if we're interrupted while waiting for the spawn
     * to finish.
     * @throws TimeoutException if the spawn didn't finish inside of the
     * timeout.
     * @see #expectClose()
     */
    public void expectClose(
                             long timeOutSeconds ) throws TimeoutException, ExpectJException {

        if( timeOutSeconds < -1 ) {
            throw new IllegalArgumentException( "Timeout must be >= -1, was " + timeOutSeconds );
        }

        LOG.debug( "Waiting for spawn to close connection..." );
        Timer tm = null;
        slave.setCloseListener( new Spawnable.CloseListener() {
            public void onClose() {

                synchronized( doneWaitingForClose ) {
                    doneWaitingForClose.notify();
                }
            }
        } );
        if( timeOutSeconds != -1 ) {
            tm = new Timer( timeOutSeconds, new TimerEventListener() {
                public void timerTimedOut() {

                    Spawn.this.timerTimedOut();
                }

                public void timerInterrupted(
                                              InterruptedException reason ) {

                    Spawn.this.timerInterrupted();
                }
            } );
            tm.startTimer();
        }
        continueReading = true;
        boolean closed = false;
        synchronized( doneWaitingForClose ) {
            while( continueReading ) {
                // Sleep if process is still running
                if( slave.isClosed() ) {
                    closed = true;
                    break;
                } else {
                    try {
                        doneWaitingForClose.wait( 500 );
                    } catch( InterruptedException e ) {
                        throw new ExpectJException( "Interrupted waiting for spawn to finish", e );
                    }
                }
            }
        }
        if( tm != null ) {
            tm.close();
        }
        if( closed ) {
            LOG.debug( "Connection to spawn closed, continueReading=" + continueReading );
        } else {
            LOG.debug( "Timed out waiting for spawn to close, continueReading=" + continueReading );
        }
        if( tm != null ) {
            LOG.debug( "Timer Status:" + tm.getStatus() );
        }
        if( !continueReading ) {
            throw new TimeoutException( "Timeout waiting for spawn to finish" );
        }

        freeResources();
    }

    /**
     * Free up system resources.
     */
    private void freeResources() {

        slave.close();
        if( interactIn != null ) {
            interactIn.stopProcessing();
        }
        if( interactOut != null ) {
            interactOut.stopProcessing();
        }
        if( interactErr != null ) {
            interactErr.stopProcessing();
        }
        if( stderrSelector != null ) {
            try {
                stderrSelector.close();
            } catch( IOException e ) {
                // Cleaning up is a best effort operation, failures are
                // logged but otherwise accepted.
                LOG.warn( "Failed cleaning up after spawn done", e );
            }
        }
        if( stdoutSelector != null ) {
            try {
                stdoutSelector.close();
            } catch( IOException e ) {
                LOG.warn( "Failed cleaning up after spawn done", e );
            }
        }
        if( toStdin != null ) {
            try {
                toStdin.close();
            } catch( IOException e ) {
                LOG.warn( "Failed cleaning up after spawn done", e );
            }
        }
    }

    /**
     * Wait the default timeout for the spawned process to finish.
     * @throws ExpectJException If something fails.
     * @throws TimeoutException if the spawn didn't finish inside of the default
     * timeout.
     * @see #expectClose(long)
     * @see ExpectJ#ExpectJ(long)
     */
    public void expectClose() throws ExpectJException, TimeoutException {

        expectClose( m_lDefaultTimeOutSeconds );
    }

    /**
     * Workhorse of the expect() and expectErr() methods.
     * @see #expect(String, long)
     * @param pattern What to look for
     * @param lTimeOutSeconds How long to look before giving up
     * @param selector A selector covering only the channel we should read from
     * @throws IOException on IO trouble waiting for pattern
     * @throws TimeoutException on timeout waiting for pattern
     */
    private void expect(
                         String pattern,
                         boolean isRegex,
                         long lTimeOutSeconds,
                         Selector selector ) throws IOException, TimeoutException {

        if( lTimeOutSeconds < -1 ) {
            throw new IllegalArgumentException( "Timeout must be >= -1, was " + lTimeOutSeconds );
        }

        if( selector.keys().size() != 1 ) {
            throw new IllegalArgumentException( "Selector key set size must be 1, was "
                                                + selector.keys().size() );
        }
        // If this cast fails somebody gave us the wrong selector.
        Pipe.SourceChannel readMe = ( Pipe.SourceChannel ) ( selector.keys().iterator().next() ).channel();

        // tell user our expectations
        LOG.info( "Expecting to match the following " + ( isRegex
                                                                 ? "regex "
                                                                 : "" ) + "pattern:\n" + pattern );

        // it is possible that the pattern we search for now, is already
        // available
        if( findMatchInInternalBuffer( pattern, isRegex ) ) {
            LOG.debug( "The expected pattern was already read" );
            return;
        }

        continueReading = true;
        boolean found = false;
        Date runUntil = null;
        if( lTimeOutSeconds > 0 ) {
            runUntil = new Date( new Date().getTime() + lTimeOutSeconds * 1000 );
        }
        while( continueReading ) {
            if( runUntil == null ) {
                selector.select();
            } else {
                long msLeft = runUntil.getTime() - new Date().getTime();
                if( msLeft > 0 ) {
                    selector.select( msLeft );
                } else {
                    LOG.debug( "no more wait time" );
                    continueReading = false;
                    break;
                }
            }

            if( selector.selectedKeys().size() == 0 ) {
                // Woke up with nothing selected, try again
                LOG.debug( "Woke up with nothing selected, try again" );
                continue;
            }

            readFromPipeAndPutInInternalBuffer( readMe );

            found = findMatchInInternalBuffer( pattern, isRegex );
            if( found ) {
                break;
            }
        }

        if( !continueReading ) {
            throw new TimeoutException( "Timeout trying to match '" + pattern + "'" );
        }
    }

    /**
     * Wait for a pattern to appear on standard error.
     * @see #expect(String, long)
     * @param pattern The case-insensitive substring to match against.
     * @param timeOutSeconds The timeout in seconds before the match fails.
     * @throws TimeoutException on timeout waiting for pattern
     * @throws IOException on IO trouble waiting for pattern
     */
    public void expectErr(
                           String pattern,
                           boolean isRegex,
                           long timeOutSeconds ) throws IOException, TimeoutException {

        expect( pattern, isRegex, timeOutSeconds, stderrSelector );
    }

    /**
     * Wait for a pattern to appear on standard out.
     * @param pattern The case-insensitive substring to match against.
     * @throws TimeoutException on timeout waiting for pattern
     * @throws IOException on IO trouble waiting for pattern
     */
    public void expect(
                        String pattern,
                        boolean isRegex ) throws IOException, TimeoutException {

        expect( pattern, isRegex, m_lDefaultTimeOutSeconds );
    }

    /**
     * Wait for a pattern to appear on standard error.
     * @param pattern The case-insensitive substring to match against.
     * @throws TimeoutException on timeout waiting for pattern
     * @throws IOException on IO trouble waiting for pattern
     * @see #expect(String)
     */
    public void expectErr(
                           String pattern,
                           boolean isRegex ) throws IOException, TimeoutException {

        expectErr( pattern, isRegex, m_lDefaultTimeOutSeconds );
    }

    /**
     * This method can be use use to check the target process status
     * before invoking {@link #send(String)}
     * @return true if the process has already exited.
     */
    public boolean isClosed() {

        return slave.isClosed();
    }

    /**
     * Retrieve the exit code of a finished process.
     * @return the exit code of the process if the process has
     * already exited.
     * @throws ExpectJException if the spawn is still running.
     */
    public int getExitValue() throws ExpectJException {

        return slave.getExitValue();
    }

    /**
     * Writes a string to the standard input of the spawned process.
     *
     * @param string The string to send.  Don't forget to terminate it with \n
     * if you want it linefed.
     * @throws IOException on IO trouble talking to spawn
     */
    public void send(
                      String string ) throws IOException {

        LOG.debug( "Sending '" + string + "'" );
        toStdin.write( string );
        toStdin.flush();
    }

    public void sendEnterKey() throws IOException {

        LOG.debug( "Sending 'ENTER'" );
        toStdin.write( '\n' );
        toStdin.flush();
    }

    /**
     * Allows the user to interact with the spawned process.
     */
    public void interact() {

        // FIXME: User input is echoed twice on the screen
        interactIn = new StreamPiper( null, System.in, slave.getStdin() );
        interactIn.start();
        interactOut = new StreamPiper( null, Channels.newInputStream( slave.getStdoutChannel() ), System.out );
        interactOut.start();
        interactErr = new StreamPiper( null, Channels.newInputStream( slave.getStderrChannel() ), System.err );
        interactErr.start();
        slave.stopPipingToStandardOut();
    }

    /**
     * This method kills the process represented by SpawnedProcess object.
     */
    public void stop() {

        slave.stop();

        freeResources();
    }

    public Object getSystemObject() {

        return slave.getSystemObject();
    }

    /**
     * Returns everything that has been received on the spawn's stdout during
     * this session.
     *
     * @return the available contents of Standard Out
     */
    public String getCurrentStandardOutContents() {

        return slave.getCurrentStandardOutContents();
    }

    /**
     * Returns everything that has been received on the spawn's stderr during
     * this session.
     *
     * @return the available contents of Standard Err
     */
    public String getCurrentStandardErrContents() {

        return slave.getCurrentStandardErrContents();
    }

    public int expectAny(
                          List<String> patterns,
                          boolean isRegex,
                          long timeout ) throws IOException, ExpectJException {

        if( timeout < -1 ) {
            throw new IllegalArgumentException( "Timeout must be >= -1, was " + timeout );
        }

        Selector selector = stdoutSelector;

        // If this cast fails somebody gave us the wrong selector.
        Pipe.SourceChannel readMe = ( Pipe.SourceChannel ) ( selector.keys().iterator().next() ).channel();

        // tell user our expectations
        StringBuilder msg = new StringBuilder();
        if( isRegex ) {
            msg.append( "Expecting to match any of the following regex patterns:" );
        } else {
            msg.append( "Expecting to match any of the following patterns:" );
        }
        int counter = 0;
        for( String pattern : patterns ) {
            msg.append( "\n[" + ( counter++ ) + "] '" + pattern + "'" );
        }
        LOG.info( msg );

        Date runUntil = null;
        if( timeout > 0 ) {
            runUntil = new Date( new Date().getTime() + timeout * 1000 );
        }
        while( true ) {
            if( runUntil == null ) {
                selector.select();
            } else {
                long msLeft = runUntil.getTime() - new Date().getTime();
                if( msLeft > 0 ) {
                    selector.select( msLeft );
                } else {
                    throw new ExpectJException( "Could not match any of the patterns" );
                }
            }

            if( selector.selectedKeys().size() == 0 ) {
                // Woke up with nothing selected, try again
                LOG.debug( "Woke up with nothing selected, try again" );
                continue;
            }

            readFromPipeAndPutInInternalBuffer( readMe );

            int patternIndex = -1;
            for( String pattern : patterns ) {
                ++patternIndex;

                if( findMatchInInternalBuffer( pattern, isRegex ) ) {
                    return patternIndex;
                }
            }
        }
    }

    public void expectAll(
                           List<String> patterns,
                           boolean isRegex,
                           long timeout ) throws IOException, TimeoutException {

        if( timeout < -1 ) {
            throw new IllegalArgumentException( "Timeout must be >= -1, was " + timeout );
        }

        Selector selector = stdoutSelector;

        // If this cast fails somebody gave us the wrong selector.
        Pipe.SourceChannel readMe = ( Pipe.SourceChannel ) ( selector.keys().iterator().next() ).channel();

        // tell user our expectations
        StringBuilder msg = new StringBuilder();
        if( isRegex ) {
            msg.append( "Expecting to match all of the following regex patterns:" );
        } else {
            msg.append( "Expecting to match all of the following patterns:" );
        }
        int counter = 0;
        for( String pattern : patterns ) {
            msg.append( "\n[" + ( counter++ ) + "] '" + pattern + "'" );
        }
        LOG.info( msg );

        Date runUntil = null;
        if( timeout > 0 ) {
            runUntil = new Date( new Date().getTime() + timeout * 1000 );
        }
        while( true ) {
            if( runUntil == null ) {
                selector.select();
            } else {
                long msLeft = runUntil.getTime() - new Date().getTime();
                if( msLeft > 0 ) {
                    selector.select( msLeft );
                } else {
                    LOG.debug( "no more wait time" );
                    continueReading = false;
                    break;
                }
            }

            if( selector.selectedKeys().size() == 0 ) {
                // Woke up with nothing selected, try again
                LOG.debug( "Woke up with nothing selected, try again" );
                continue;
            }

            readFromPipeAndPutInInternalBuffer( readMe );

            Iterator<String> it = patterns.iterator();
            while( it.hasNext() ) {
                String pattern = it.next();

                boolean matchedThisPattern = findMatchInInternalBuffer( pattern, isRegex );
                if( matchedThisPattern ) {
                    // this pattern is matched
                    it.remove();
                    // see if can match the next one now
                } else {
                    // this pattern is NOT matched
                    // break the cycle, we will try again later with the same
                    // pattern
                    break;
                }
            }

            if( patterns.size() == 0 ) {
                return;
            }
        }

        if( patterns.size() != 0 ) {
            StringBuilder errMsg = new StringBuilder( "Timed out without matching the following "
                                                      + ( isRegex
                                                                 ? "regex "
                                                                 : "" ) + "patterns:" );
            for( String pattern : patterns ) {
                errMsg.append( "\n'" + pattern + "'" );
            }

            throw new TimeoutException( errMsg.toString() );
        }
    }

    private void readFromPipeAndPutInInternalBuffer(
                                                     Pipe.SourceChannel readMe ) throws IOException {

        ByteBuffer buffer = newBuffer();

        if( readMe.read( buffer ) == -1 ) {
            // End of stream
            throw new IOException( "End of stream reached, no match found" );
        }

        // go to beginning
        buffer.rewind();

        // read all bytes
        for( int i = 0; i < buffer.limit(); i++ ) {
            byte b = buffer.get( i );
            if( b == 0 ) {
                // reached buffer end
                break;
            }

            currentBuffer.append( ( char ) b );
        }

        // displayCurrentBuffer("CURRENT BUFFER");
    }

    private boolean findMatchInInternalBuffer(
                                               String pattern,
                                               boolean isRegex ) {

        if( isRegex ) {
            // regular expression patter

            Pattern patternObject = Pattern.compile( pattern, Pattern.DOTALL | Pattern.CASE_INSENSITIVE );
            Matcher matcher = patternObject.matcher( currentBuffer.toString() );

            if( matcher.find() ) {
                LOG.info( "Matched regex pattern '" + pattern + "'" );

                // We expect user provided a matcher that is targeting 1 group
                // only,
                // but just in case we will process the last matched group
                int patternIndexEnd = matcher.end( matcher.groupCount() );

                // displayCurrentBuffer("OLD BUFFER");
                currentBuffer.delete( 0, patternIndexEnd );
                // displayCurrentBuffer("NEW BUFFER");
                pendingToMatchBuffer.setLength( 0 );
                pendingToMatchBuffer.append( currentBuffer );
                return true;
            } else {

                LOG.info( "Did not match regex pattern '" + pattern + "'" );
                return false;
            }
        } else {
            // plain text pattern

            int patternIndex = currentBuffer.toString().toUpperCase().indexOf( pattern.toUpperCase() );

            if( patternIndex != -1 ) {
                LOG.info( "Matched pattern '" + pattern + "'" );

                // displayCurrentBuffer("OLD BUFFER");
                currentBuffer.delete( 0, patternIndex + pattern.length() );
                // displayCurrentBuffer("NEW BUFFER");
                pendingToMatchBuffer.setLength( 0 );
                pendingToMatchBuffer.append( currentBuffer );
                return true;
            } else {
                LOG.info( "Did not match pattern '" + pattern + "'" );
                return false;
            }
        }
    }

    public void sendEnterKeyInLoop(
                                    String intermediatePattern,
                                    String finalPattern,
                                    int maxLoopTimes ) throws IOException, TimeoutException,
                                                      InterruptedException {

        // tell user our expectations
        LOG.info( "Loop for no more than " + maxLoopTimes + " times.\nExpecting intermediate pattern is '"
                  + intermediatePattern + "'\nExpecting final pattern is '" + finalPattern + "'" );

        boolean matchedFinalPattern = false;
        String allContent = "";
        for( int i = 0; i < maxLoopTimes; i++ ) {

            LOG.debug( "Loop with ENTER key for " + i + "th time" );
            String readContent = readInBuffer( 1 );
            allContent = allContent + readContent;

            if( readContent.toUpperCase().contains( finalPattern.toUpperCase() ) ) {
                LOG.info( "Final pattern '" + finalPattern + "' is found. We will exit now" );

                send( "Y" );
                sendEnterKey();
                matchedFinalPattern = true;
                break;
            } else if( readContent.toUpperCase().contains( intermediatePattern.toUpperCase() ) ) {
                LOG.info( "Middle pattern '" + intermediatePattern + "' is found. We will loop again" );

                sendEnterKey();
            } else {
                LOG.warn( "Non of the patterns is found" );

                sendEnterKey();
            }
        }

        if( !matchedFinalPattern ) {
            throw new TimeoutException( "Did not match the expected final pattern '" + finalPattern + "'" );
        }
    }

    private String readInBuffer(
                                 long readTimeSeconds ) throws IOException {

        StringBuffer bigBuffer = new StringBuffer();
        Selector selector = stdoutSelector;

        // If this cast fails somebody gave us the wrong selector.
        Pipe.SourceChannel readMe = ( Pipe.SourceChannel ) ( selector.keys().iterator().next() ).channel();

        final Date endTime = new Date( new Date().getTime() + readTimeSeconds * 1000 );

        ByteBuffer buffer = newBuffer();
        while( true ) {
            buffer = newBuffer();
            long msLeft = endTime.getTime() - new Date().getTime();
            if( msLeft > 0 ) {
                selector.select( msLeft );
            } else {
                LOG.debug( "no more wait time" );
                break;
            }

            if( selector.selectedKeys().size() == 0 ) {
                LOG.debug( "Woke up with nothing selected, try again" );
                continue;
            }

            // buffer.rewind();
            int nRead = readMe.read( buffer );
            if( nRead == -1 ) {
                LOG.debug( "End of stream reached" );
                break;
            } else if( nRead > 0 ) {
                buffer.rewind();
                for( int i = 0; i < buffer.limit(); i++ ) {
                    byte b = buffer.get( i );
                    if( b != 0 ) {
                        bigBuffer.append( ( char ) b );
                    }
                }
            }
        }

        return bigBuffer.toString();
    }

    @SuppressWarnings("unused")
    private void displayCurrentBuffer(
                                       String prefix ) {

        LOG.warn( prefix + "\n'''''''''''\n" + currentBuffer.toString() + "\n'''''''''''''" );
    }

    private ByteBuffer newBuffer() {

        return ByteBuffer.allocate( 100 * 1024 );
    }
}
