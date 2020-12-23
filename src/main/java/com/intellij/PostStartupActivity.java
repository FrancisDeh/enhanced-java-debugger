package com.intellij;

import com.intellij.debugger.engine.JavaDebugProcess;
import com.intellij.debugger.engine.JavaStackFrame;
import com.intellij.debugger.engine.evaluation.EvaluateException;
import com.intellij.debugger.impl.DebuggerContextImpl;
import com.intellij.debugger.impl.DebuggerContextListener;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.debugger.ui.DebuggerContentInfo;
import com.intellij.execution.ui.RunnerLayoutUi;
import com.intellij.execution.ui.layout.PlaceInGrid;
import com.intellij.ide.ui.customization.CustomActionsSchema;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.content.Content;
import com.intellij.xdebugger.*;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointListener;
import com.intellij.xdebugger.breakpoints.XBreakpointManager;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import com.intellij.xdebugger.frame.*;
import com.intellij.xdebugger.impl.XDebugSessionImpl;
import com.intellij.xdebugger.impl.actions.XDebuggerActions;
import com.intellij.xdebugger.impl.frame.XValueMarkers;
import com.intellij.xdebugger.impl.frame.XVariablesView;
import com.intellij.xdebugger.impl.frame.XWatchesViewImpl;
import com.intellij.xdebugger.impl.ui.tree.ValueMarkup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PostStartupActivity implements StartupActivity {

    private static final Logger logger = Logger.getLogger(PostStartupActivity.class.getName());
    //holds the breakpoints for a debugging session
    private static final HashMap<Integer, XBreakpoint> breakPointsMap = new HashMap<>();
    //holds the hit counts for a breakpoint
    private static final HashMap<Integer, Integer> breakPointsHitCounts = new HashMap<>();
    //current method name been traversed by debugger
    String methodName = null;
    RunnerLayoutUi ui;
    private final boolean myWatchesInVariables = Registry.is("debugger.watches.in.variables");
    protected XWatchesViewImpl myWatchesView;
    XValueMarkers<?, ?> markers;
    Set<XValue> xValueSet = new HashSet<>();

    @Override
    public void runActivity(@NotNull Project project) {
        logger.log(Level.INFO,"We are in the startup activity class");

        attachDebugStartListener(project);
        attachBreakPointListener(project);
        getCurrentBreakPoints(project);
    }


    private void getCurrentBreakPoints(Project project) {
        XBreakpointManager manager = XDebuggerManager.getInstance(project).getBreakpointManager();
//       XDebuggerManager.getInstance(project).getCurrentSession().getUI().addListener();



        Arrays.stream(manager.getAllBreakpoints()).forEach(xBreakpoint -> {
            System.out.println( "pre-existing breakpoints");
            //add to breakpoints if not an exception breakpoint type
            //todo; keep track of the stack frame file
            if (!xBreakpoint.getType().getTitle().equals("Java Exception Breakpoints")) {
                addBreakPoint(xBreakpoint);
            }
        });
    }

    private void attachDebugStartListener(Project project) {
        System.out.println( "Debugger manager listener");

        project.getMessageBus().connect().subscribe(XDebuggerManager.TOPIC, new XDebuggerManagerListener() {
            @Override
            public void processStarted(@NotNull XDebugProcess debugProcess) {
                System.out.println( "Process started");
                attachDebugBreakListener(debugProcess);
            }

            @Override
            public void processStopped(@NotNull XDebugProcess debugProcess) {
                System.out.println( "Process stopped");
            }

            @Override
            public void currentSessionChanged(@Nullable XDebugSession previousSession, @Nullable XDebugSession currentSession) {
                System.out.println( "Process session changed");
            }

        });

    }

    private void attachBreakPointListener(Project project) {
        System.out.println( "Attaching breakpoint listener");
        project.getMessageBus().connect().subscribe(XBreakpointListener.TOPIC, new XBreakpointListener() {
            @Override
            public void breakpointAdded(@NotNull XBreakpoint breakpoint) {
                addBreakPoint(breakpoint);
            }

            @Override
            public void breakpointRemoved(@NotNull XBreakpoint breakpoint) {
                removeBreakPoint(breakpoint);
            }

            @Override
            public void breakpointChanged(@NotNull XBreakpoint breakpoint) {
                updateBreakPoint(breakpoint);
            }
        });

    }


    private void attachDebugBreakListener(@NotNull XDebugProcess debugProcess) {
        System.out.println( "Attaching session listener");


        debugProcess.getSession().addSessionListener(new XDebugSessionListener() {
            @Override
            public void sessionPaused() {
                try {
                    attachComputeChildrenListener(Objects.requireNonNull(debugProcess.getSession()));
                } catch (EvaluateException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void sessionResumed() {
                System.out.println( "Session resumed");
            }

            @Override
            public void sessionStopped() {
                System.out.println( "Session stopped");
                //clear breakpoints data
                breakPointsMap.clear();
                breakPointsHitCounts.clear();
            }

            @Override
            public void stackFrameChanged() {
                System.out.println( "stack frame changed");
            }

            @Override
            public void beforeSessionResume() {
                System.out.println( "before session resumed");
            }

            @Override
            public void settingsChanged() {
                System.out.println( "settings changed");
                trackMarkedObjects(debugProcess);
            }
        });
    }

    private void attachComputeChildrenListener(XDebugSession xDebugSession) throws EvaluateException {
        XStackFrame currentStackFrame = xDebugSession.getCurrentStackFrame();
        System.out.println( "Current stack frame");

        System.out.println( "Frame stack file");
        System.out.println( Objects.requireNonNull(currentStackFrame.getSourcePosition()).getFile().toString());

        String method = ((JavaStackFrame) currentStackFrame).getStackFrameProxy().location().method().name();
        System.out.println( "method name: " + method);
        System.out.println( "Stack position");
        int currentStackLine = Objects.requireNonNull(currentStackFrame.getSourcePosition()).getLine();
        System.out.println(currentStackLine);

        handleMarkedObjectPresentationToUI(xDebugSession, method);
        displayAllBreakpointsAndTrack(currentStackLine);

        Objects.requireNonNull(currentStackFrame).computeChildren(new XCompositeNode() {
            @Override
            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                for (int c = 0; c < children.size(); c++) {
                    XValue childValue = children.getValue(c);
                    boolean isAdded = xValueSet.add(childValue);
                    System.out.println("Is added to set " + isAdded);
                    System.out.println( "Scope Variable:  " + childValue.toString());
                }
            }

            @Override
            public void tooManyChildren(int remaining) {

            }

            @Override
            public void tooManyChildren(int remaining, @NotNull Runnable addNextChildren) {

            }

            @Override
            public void setAlreadySorted(boolean alreadySorted) {

            }

            @Override
            public void setErrorMessage(@NotNull String errorMessage) {

            }

            @Override
            public void setErrorMessage(@NotNull String errorMessage, @Nullable XDebuggerTreeNodeHyperlink link) {

            }

            @Override
            public void setMessage(@NotNull String message, @Nullable Icon icon, @NotNull SimpleTextAttributes attributes, @Nullable XDebuggerTreeNodeHyperlink link) {

            }
        });
    }

    private void handleMarkedObjectPresentationToUI(XDebugSession xDebugSession, String method) {
        //check and see if function method currently traversed by the debugger is in a different method
        //from the previous one, if true, modify the debugger variables view to display our saved marked objects
        if(methodName != null && !methodName.equals(method)) {
            //we are in a different method
            System.out.println("We are in a different method");
             ui = xDebugSession.getUI();

             //list all the xValues
            if(markers == null) return;
            System.out.println("About attaching marked objects");
            xValueSet.forEach(xValue -> {
                System.out.println("**********************************************************");
                System.out.println("Getting mark up for " + xValue.toString());
                ValueMarkup valueMarkup = markers.getMarkup(xValue);
                if(valueMarkup != null) {
                    System.out.println("Markup " + valueMarkup.toString());
                    ((XDebugSessionImpl) xDebugSession).getValueMarkers().markValue(xValue, valueMarkup);
                    System.out.println("Finished attaching markup");
                }
                System.out.println("**********************************************************");
            });

            //todo: invoke on a separate thread
            ui.addContent(createVariablesContent((XDebugSessionImpl) xDebugSession), 0, PlaceInGrid.center, false);
            if (!myWatchesInVariables) {
                ui.addContent(createWatchesContent((XDebugSessionImpl) xDebugSession), 0, PlaceInGrid.right, false);
            }

//            xDebugSession.rebuildViews();
        }
        methodName = method;
    }

    private Content createWatchesContent(@NotNull XDebugSessionImpl session) {
        myWatchesView = new XWatchesViewImpl(session, myWatchesInVariables);
//        registerView(DebuggerContentInfo.WATCHES_CONTENT, myWatchesView);
        Content watchesContent = ui.createContent(DebuggerContentInfo.WATCHES_CONTENT, myWatchesView.getPanel(),
                XDebuggerBundle.message("debugger.session.tab.watches.title"), null, myWatchesView.getDefaultFocusedComponent());
        watchesContent.setCloseable(false);
        return watchesContent;
    }

    private Content createVariablesContent(@NotNull XDebugSessionImpl session) {
        XVariablesView variablesView;
        if (myWatchesInVariables) {
            variablesView = myWatchesView = new XWatchesViewImpl(session, myWatchesInVariables);
        } else {
            variablesView = new XVariablesView(session);
        }
//        registerView(DebuggerContentInfo.VARIABLES_CONTENT, variablesView);
        Content result = ui.createContent(DebuggerContentInfo.VARIABLES_CONTENT, variablesView.getPanel(),
                XDebuggerBundle.message("debugger.session.tab.variables.title"),
                null, variablesView.getDefaultFocusedComponent());
        result.setCloseable(false);

        ActionGroup group = getCustomizedActionGroup(XDebuggerActions.VARIABLES_TREE_TOOLBAR_GROUP);
        result.setActions(group, ActionPlaces.DEBUGGER_TOOLBAR, variablesView.getTree());
        return result;
    }

    public static ActionGroup getCustomizedActionGroup(final String id) {
        return (ActionGroup) CustomActionsSchema.getInstance().getCorrectedAction(id);
    }

    private void addBreakPoint(XBreakpoint breakpoint) {
        System.out.println( "breakpoint added");
        breakPointsMap.put(breakpoint.getSourcePosition().getLine(), breakpoint);
        System.out.println( "Total breakpoints " + breakPointsMap.size());
        addHitCount(breakpoint.getSourcePosition().getLine(), 0);
    }

    private void removeBreakPoint(XBreakpoint breakpoint) {
        System.out.println( "breakpoint removed");
        breakPointsMap.remove(breakpoint.getSourcePosition().getLine());
        System.out.println( "Total breakpoints " + breakPointsMap.size());
        removeHitCount(breakpoint.getSourcePosition().getLine());
    }

    private void updateBreakPoint(XBreakpoint breakpoint) {
        System.out.println( "breakpoint changed");
        breakPointsMap.put(breakpoint.getSourcePosition().getLine(), breakpoint);
        System.out.println( "Total breakpoints " + breakPointsMap.size());
    }

    private void addHitCount(int breakPointLine, int count) {
        System.out.println( "Hit count added");
        breakPointsHitCounts.put(breakPointLine, count);
    }

    private void removeHitCount(int breakPointLine) {
        System.out.println( "Hit count removed");
        breakPointsHitCounts.remove(breakPointLine);
    }

    private void displayAllBreakpointsAndTrack(int currentStackLine) {
        breakPointsMap.forEach((line, xBreakpoint) -> {
            System.out.println( "----------------------------------------------------");
            System.out.println( "Breakpoint line: ");
            System.out.println( line.toString());
            System.out.println( "Previous Hit Count: ");
            System.out.println( breakPointsHitCounts.get(line).toString());

            //update the hit count if the current stack line is on the hit count line
            if (currentStackLine == line) {
                addHitCount(line, breakPointsHitCounts.get(line) + 1);
            }
            System.out.println( "Current Hit Count: ");
            System.out.println( breakPointsHitCounts.get(line).toString());

            System.out.println( "----------------------------------------------------");
        });

    }

    private void trackMarkedObjects(XDebugProcess debugProcess) {
        //todo: improve logic to hold all incoming markers, not replace the already existing markers
      markers = ((XDebugSessionImpl) debugProcess.getSession()).getValueMarkers();
        System.out.println( "Marked Objects");

        markers.getAllMarkers().forEach((o, valueMarkup) -> {
            System.out.println( o.toString());
            System.out.println( valueMarkup.getText());
        });
    }
}
