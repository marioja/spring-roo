package org.springframework.roo.shell.jline;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import jline.ANSIBuffer;
import jline.ANSIBuffer.ANSICodes;
import jline.ConsoleReader;
import jline.WindowsTerminal;

import org.springframework.roo.shell.AbstractShell;
import org.springframework.roo.shell.CommandMarker;
import org.springframework.roo.shell.ExitShellRequest;
import org.springframework.roo.shell.Shell;
import org.springframework.roo.shell.Tailor;
import org.springframework.roo.shell.event.ShellStatus;
import org.springframework.roo.shell.event.ShellStatus.Status;
import org.springframework.roo.shell.event.ShellStatusListener;
import org.springframework.roo.support.util.Assert;
import org.springframework.roo.support.util.ClassUtils;
import org.springframework.roo.support.util.FileCopyUtils;
import org.springframework.roo.support.util.IOUtils;
import org.springframework.roo.support.util.OsUtils;
import org.springframework.roo.support.util.StringUtils;

/**
 * Uses the feature-rich <a
 * href="http://sourceforge.net/projects/jline/">JLine</a> library to provide an
 * interactive shell.
 * <p>
 * Due to Windows' lack of color ANSI services out-of-the-box, this
 * implementation automatically detects the classpath presence of <a
 * href="http://jansi.fusesource.org/">Jansi</a> and uses it if present. This
 * library is not necessary for *nix machines, which support colour ANSI without
 * any special effort. This implementation has been written to use reflection in
 * order to avoid hard dependencies on Jansi.
 * 
 * @author Ben Alex
 * @since 1.0
 */
public abstract class JLineShell extends AbstractShell implements
        CommandMarker, Shell, Runnable {

    private static class FlashInfo {
        Level flashLevel;
        String flashMessage;
        long flashMessageUntil;
        int rowNumber;
    }

    private static final String ANSI_CONSOLE_CLASSNAME = "org.fusesource.jansi.AnsiConsole";
    private static final boolean APPLE_TERMINAL = Boolean
            .getBoolean("is.apple.terminal");
    private static final String BEL = "\007";
    private static final char ESCAPE = 27;

    private static final boolean JANSI_AVAILABLE = ClassUtils.isPresent(
            ANSI_CONSOLE_CLASSNAME, JLineShell.class.getClassLoader());
    private boolean developmentMode = false;
    private final DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private FileWriter fileLog;
    /** key: slot name, value: flashInfo instance */
    private final Map<String, FlashInfo> flashInfoMap = new HashMap<String, FlashInfo>();
    private ConsoleReader reader;
    /** key: row number, value: eraseLineFromPosition */
    private final Map<Integer, Integer> rowErasureMap = new HashMap<Integer, Integer>();
    private boolean shutdownHookFired = false; // ROO-1599

    protected ShellStatusListener statusListener; // ROO-836

    /**
     * Should be called by a subclass before deactivating the shell.
     */
    protected void closeShell() {
        // Notify we're closing down (normally our status is already
        // shutting_down, but if it was a CTRL+C via the o.s.r.bootstrap.Main
        // hook)
        setShellStatus(Status.SHUTTING_DOWN);
        if (statusListener != null) {
            removeShellStatusListener(statusListener);
        }
    }

    private ConsoleReader createAnsiWindowsReader() throws Exception {
        // Get decorated OutputStream that parses ANSI-codes
        final PrintStream ansiOut = (PrintStream) ClassUtils
                .forName(ANSI_CONSOLE_CLASSNAME,
                        JLineShell.class.getClassLoader()).getMethod("out")
                .invoke(null);
        final WindowsTerminal ansiTerminal = new WindowsTerminal() {
            @Override
            public boolean isANSISupported() {
                return true;
            }
        };
        ansiTerminal.initializeTerminal();
        // Make sure to reset the original shell's colors on shutdown by closing
        // the stream
        statusListener = new ShellStatusListener() {
            public void onShellStatusChange(final ShellStatus oldStatus,
                    final ShellStatus newStatus) {
                if (newStatus.getStatus().equals(Status.SHUTTING_DOWN)) {
                    ansiOut.close();
                }
            }
        };
        addShellStatusListener(statusListener);

        return new ConsoleReader(new FileInputStream(FileDescriptor.in),
                new PrintWriter(new OutputStreamWriter(ansiOut,
                // Default to Cp850 encoding for Windows console output
                // (ROO-439)
                        System.getProperty(
                                "jline.WindowsTerminal.output.encoding",
                                "Cp850"))), null, ansiTerminal);
    }

    // Externally synchronized via the two calling methods having a mutex on
    // flashInfoMap
    private void doAnsiFlash(final int row, final Level level,
            final String message) {
        final ANSIBuffer buff = JLineLogHandler.getANSIBuffer();
        if (APPLE_TERMINAL) {
            buff.append(ESCAPE + "7");
        }
        else {
            buff.append(ANSICodes.save());
        }

        // Figure out the longest line we're presently displaying (or were) and
        // erase the line from that position
        int mostFurtherLeftColNumber = Integer.MAX_VALUE;
        for (final Integer candidate : rowErasureMap.values()) {
            if (candidate < mostFurtherLeftColNumber) {
                mostFurtherLeftColNumber = candidate;
            }
        }

        if (mostFurtherLeftColNumber == Integer.MAX_VALUE) {
            // There is nothing to erase
        }
        else {
            buff.append(ANSICodes.gotoxy(row, mostFurtherLeftColNumber));
            // Clear what was present on the line
            buff.append(ANSICodes.clreol());
        }

        if (("".equals(message))) {
            // They want the line blank; we've already achieved this if needed
            // via the erasing above
            // Just need to record we no longer care about this line the next
            // time doAnsiFlash is invoked
            rowErasureMap.remove(row);
        }
        else {
            if (shutdownHookFired) {
                return; // ROO-1599
            }
            // They want some message displayed
            int startFrom = (reader.getTermwidth() - message.length()) + 1;
            if (startFrom < 1) {
                startFrom = 1;
            }
            buff.append(ANSICodes.gotoxy(row, startFrom));
            buff.reverse(message);
            // Record we want to erase from this positioning next time (so we
            // clean up after ourselves)
            rowErasureMap.put(row, startFrom);
        }
        if (APPLE_TERMINAL) {
            buff.append(ESCAPE + "8");
        }
        else {
            buff.append(ANSICodes.restore());
        }

        final String stg = buff.toString();
        try {
            reader.printString(stg);
            reader.flushConsole();
        }
        catch (final IOException ignored) {
        }
    }

    /**
     * Executes the single command. There is possibility to adjust command by
     * implementation of {@link Tailor} interface. The output of Tailor can be
     * one or many commands that are executed one by one.
     * 
     * @param line - command line
     */
    // @Override
    // public boolean executeCommand(final String line) {
    // List<String> commands = null;
    // if (getTailor() != null) {
    // commands = getTailor().sew(line);
    // }
    // if (CollectionUtils.isEmpty(commands)) {
    // return super.executeCommand(line);
    // }
    // for (final String command : commands) {
    // logger.info("Tailored: " + command);
    // if (!super.executeCommand(command)) {
    // return false;
    // }
    // }
    // return true;
    // }

    @Override
    public void flash(final Level level, final String message, final String slot) {
        Assert.notNull(level, "Level is required for a flash message");
        Assert.notNull(message, "Message is required for a flash message");
        Assert.hasText(slot, "Slot name must be specified for a flash message");

        if (Shell.WINDOW_TITLE_SLOT.equals(slot)) {
            if ((reader != null) && reader.getTerminal().isANSISupported()) {
                // We can probably update the window title, as requested
                if (StringUtils.isBlank(message)) {
                    System.out.println("No text");
                }

                final ANSIBuffer buff = JLineLogHandler.getANSIBuffer();
                buff.append(ESCAPE + "]0;").append(message).append(BEL);
                final String stg = buff.toString();
                try {
                    reader.printString(stg);
                    reader.flushConsole();
                }
                catch (final IOException ignored) {
                }
            }

            return;
        }
        if (((reader != null) && !reader.getTerminal().isANSISupported())) {
            super.flash(level, message, slot);
            return;
        }
        synchronized (flashInfoMap) {
            FlashInfo flashInfo = flashInfoMap.get(slot);

            if ("".equals(message)) {
                // Request to clear the message, but give the user some time to
                // read it first
                if (flashInfo == null) {
                    // We didn't have a record of displaying it in the first
                    // place, so just quit
                    return;
                }
                flashInfo.flashMessageUntil = System.currentTimeMillis() + 1500;
            }
            else {
                // Display this message displayed until further notice
                if (flashInfo == null) {
                    // Find a row for this new slot; we basically take the first
                    // line number we discover
                    flashInfo = new FlashInfo();
                    flashInfo.rowNumber = Integer.MAX_VALUE;
                    outer: for (int i = 1; i < Integer.MAX_VALUE; i++) {
                        for (final FlashInfo existingFlashInfo : flashInfoMap
                                .values()) {
                            if (existingFlashInfo.rowNumber == i) {
                                // Veto, let's try the new candidate row number
                                continue outer;
                            }
                        }
                        // If we got to here, nobody owns this row number, so
                        // use it
                        flashInfo.rowNumber = i;
                        break outer;
                    }

                    // Store it
                    flashInfoMap.put(slot, flashInfo);
                }
                // Populate the instance with the latest data
                flashInfo.flashMessageUntil = Long.MAX_VALUE;
                flashInfo.flashLevel = level;
                flashInfo.flashMessage = message;

                // Display right now
                doAnsiFlash(flashInfo.rowNumber, flashInfo.flashLevel,
                        flashInfo.flashMessage);
            }
        }
    }

    private void flashMessageRenderer() {
        if (!reader.getTerminal().isANSISupported()) {
            return;
        }
        // Setup a thread to ensure flash messages are displayed and cleared
        // correctly
        final Thread t = new Thread(new Runnable() {
            public void run() {
                while (!shellStatus.getStatus().equals(Status.SHUTTING_DOWN)
                        && !shutdownHookFired) {
                    synchronized (flashInfoMap) {
                        final long now = System.currentTimeMillis();

                        final Set<String> toRemove = new HashSet<String>();
                        for (final String slot : flashInfoMap.keySet()) {
                            final FlashInfo flashInfo = flashInfoMap.get(slot);

                            if (flashInfo.flashMessageUntil < now) {
                                // Message has expired, so clear it
                                toRemove.add(slot);
                                doAnsiFlash(flashInfo.rowNumber, Level.ALL, "");
                            }
                            else {
                                // The expiration time for this message has not
                                // been reached, so preserve it
                                doAnsiFlash(flashInfo.rowNumber,
                                        flashInfo.flashLevel,
                                        flashInfo.flashMessage);
                            }
                        }
                        for (final String slot : toRemove) {
                            flashInfoMap.remove(slot);
                        }
                    }
                    try {
                        Thread.sleep(200);
                    }
                    catch (final InterruptedException ignore) {
                    }
                }
            }
        }, "Spring Roo JLine Flash Message Manager");
        t.start();
    }

    /**
     * Obtains the "roo.home" from the system property, falling back to the
     * current working directory if missing.
     * 
     * @return the 'roo.home' system property
     */
    @Override
    protected String getHomeAsString() {
        String rooHome = System.getProperty("roo.home");
        if (rooHome == null) {
            try {
                rooHome = new File(".").getCanonicalPath();
            }
            catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return rooHome;
    }

    public String getStartupNotifications() {
        return null;
    }

    public boolean isDevelopmentMode() {
        return developmentMode;
    }

    @Override
    protected void logCommandToOutput(final String processedLine) {
        if (fileLog == null) {
            openFileLogIfPossible();
            if (fileLog == null) {
                // Still failing, so give up
                return;
            }
        }
        try {
            fileLog.write(processedLine + "\n"); // Unix line endings only from
                                                 // Roo
            fileLog.flush(); // So tail -f will show it's working
            if (getExitShellRequest() != null) {
                // Shutting down, so close our file (we can always reopen it
                // later if needed)
                fileLog.write("// Spring Roo " + versionInfo()
                        + " log closed at " + df.format(new Date()) + "\n");
                IOUtils.closeQuietly(fileLog);
                fileLog = null;
            }
        }
        catch (final IOException ignoreIt) {
        }
    }

    private void openFileLogIfPossible() {
        try {
            fileLog = new FileWriter("log.roo", true);
            // First write, so let's record the date and time of the first user
            // command
            fileLog.write("// Spring Roo " + versionInfo() + " log opened at "
                    + df.format(new Date()) + "\n");
            fileLog.flush();
        }
        catch (final IOException ignoreIt) {
        }
    }

    public void promptLoop() {
        setShellStatus(Status.USER_INPUT);
        String line;

        try {
            while ((exitShellRequest == null)
                    && ((line = reader.readLine()) != null)) {
                JLineLogHandler.resetMessageTracking();
                setShellStatus(Status.USER_INPUT);

                if ("".equals(line)) {
                    continue;
                }

                executeCommand(line);
            }
        }
        catch (final IOException ioe) {
            throw new IllegalStateException("Shell line reading failure", ioe);
        }

        setShellStatus(Status.SHUTTING_DOWN);
    }

    private void removeHandlers(final Logger l) {
        final Handler[] handlers = l.getHandlers();
        if ((handlers != null) && (handlers.length > 0)) {
            for (final Handler h : handlers) {
                l.removeHandler(h);
            }
        }
    }

    public void run() {
        try {
            if (JANSI_AVAILABLE && OsUtils.isWindows()) {
                try {
                    reader = createAnsiWindowsReader();
                }
                catch (final Exception e) {
                    // Try again using default ConsoleReader constructor
                    logger.warning("Can't initialize jansi AnsiConsole, falling back to default: "
                            + e);
                }
            }
            if (reader == null) {
                reader = new ConsoleReader();
            }
        }
        catch (final IOException ioe) {
            throw new IllegalStateException("Cannot start console class", ioe);
        }

        setPromptPath(null);

        final JLineLogHandler handler = new JLineLogHandler(reader, this);
        JLineLogHandler.prohibitRedraw(); // Affects this thread only
        final Logger mainLogger = Logger.getLogger("");
        removeHandlers(mainLogger);
        mainLogger.addHandler(handler);

        reader.addCompletor(new JLineCompletorAdapter(getParser()));

        reader.setBellEnabled(true);
        if (Boolean.getBoolean("jline.nobell")) {
            reader.setBellEnabled(false);
        }

        // reader.setDebug(new PrintWriter(new FileWriter("writer.debug",
        // true)));

        openFileLogIfPossible();

        // Try to build previous command history from the project's log
        try {
            final String logFileContents = FileCopyUtils.copyToString(new File(
                    "log.roo"));
            final String[] logEntries = logFileContents
                    .split(StringUtils.LINE_SEPARATOR);
            // LIFO
            for (final String logEntry : logEntries) {
                if (!logEntry.startsWith("//")) {
                    reader.getHistory().addToHistory(logEntry);
                }
            }
        }
        catch (final IOException ignored) {
        }

        flashMessageRenderer();

        logger.info(version(null));

        flash(Level.FINE, "Spring Roo " + versionInfo(),
                Shell.WINDOW_TITLE_SLOT);

        logger.info("Welcome to Spring Roo. For assistance press "
                + completionKeys + " or type \"hint\" then hit ENTER.");

        final String startupNotifications = getStartupNotifications();
        if (StringUtils.hasText(startupNotifications)) {
            logger.info(startupNotifications);
        }

        setShellStatus(Status.STARTED);

        // Monitor CTRL+C initiated shutdowns (ROO-1599)
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                shutdownHookFired = true;
                // We don't need to closeShell(), as the shutdown hook in
                // o.s.r.bootstrap.Main calls stop() which calls
                // JLineShellComponent.deactivate() and that calls closeShell()
            }
        }, "Spring Roo JLine Shutdown Hook"));

        // Handle any "execute-then-quit" operation
        final String rooArgs = System.getProperty("roo.args");
        if ((rooArgs != null) && !"".equals(rooArgs)) {
            setShellStatus(Status.USER_INPUT);
            final boolean success = executeCommand(rooArgs);
            if (exitShellRequest == null) {
                // The command itself did not specify an exit shell code, so
                // we'll fall back to something sensible here
                executeCommand("quit"); // ROO-839
                exitShellRequest = success ? ExitShellRequest.NORMAL_EXIT
                        : ExitShellRequest.FATAL_EXIT;
            }
            setShellStatus(Status.SHUTTING_DOWN);
        }
        else {
            // Normal RPEL processing
            promptLoop();
        }
    }

    public void setDevelopmentMode(final boolean developmentMode) {
        JLineLogHandler.setIncludeThreadName(developmentMode);
        JLineLogHandler.setSuppressDuplicateMessages(!developmentMode); // We
                                                                        // want
                                                                        // to
                                                                        // see
                                                                        // duplicate
                                                                        // messages
                                                                        // during
                                                                        // development
                                                                        // time
                                                                        // (ROO-1873)
        this.developmentMode = developmentMode;
    }

    @Override
    public void setPromptPath(final String path) {
        setPromptPath(path, false);
    }

    @Override
    public void setPromptPath(final String path, final boolean overrideStyle) {
        if (reader.getTerminal().isANSISupported()) {
            final ANSIBuffer ansi = JLineLogHandler.getANSIBuffer();
            if ((path == null) || "".equals(path)) {
                shellPrompt = ansi.yellow(ROO_PROMPT).toString();
            }
            else {
                if (overrideStyle) {
                    ansi.append(path);
                }
                else {
                    ansi.cyan(path);
                }
                shellPrompt = ansi.yellow(" " + ROO_PROMPT).toString();
            }
        }
        else {
            // The superclass will do for this non-ANSI terminal
            super.setPromptPath(path);
        }

        // The shellPrompt is now correct; let's ensure it now gets used
        reader.setDefaultPrompt(AbstractShell.shellPrompt);
    }
}
