package dev.agaminggod.arenaagents.control;

import com.google.gson.JsonParser;
import java.awt.event.MouseEvent;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Exercises the real Swing graph without opening a window or starting Minecraft. */
public final class LiveTaskWindowRenderVerification {
 public static void main(String[] args) throws Exception {
  Path output = Path.of("build/reports/live-task-windows");
  Files.createDirectories(output);
  SwingUtilities.invokeAndWait(() -> {
   try {
    Class<?> outer = Class.forName("dev.agaminggod.arenaagents.client.control.LiveAgentWindows");
    Class<?> graphClass = Class.forName(outer.getName() + "$Graph");
    var constructor = graphClass.getDeclaredConstructor(); constructor.setAccessible(true);
    JPanel graph = (JPanel) constructor.newInstance();
    set(outer, "graph", graph);
    var setData = graphClass.getDeclaredMethod("setData", com.google.gson.JsonObject.class); setData.setAccessible(true);
    var centerOf = graphClass.getDeclaredMethod("centerOf", String.class); centerOf.setAccessible(true);
    var snapshot = JsonParser.parseString("""
      {"goalRevision":1,"goal":"Beat the game and kill the ender dragon","active":true,"verified":false,"revision":2,"generatedAt":1,
       "events":[{"stage":"live_lifecycle","message":"Goal lifecycle operation 'start' accepted.","sequence":1,"at":1},
        {"stage":"live_planner","message":"Native turn queued with ordinary priority.","sequence":2,"at":2},
        {"stage":"live_provider","message":"Provider native_turn request started (attempt 1).","sequence":3,"at":3},
        {"stage":"live_agent_message","message":"Need to find logs. Heading to the birch forest to the east before night falls, then crafting a table and wooden pickaxe.","sequence":4,"at":4},
        {"stage":"live_retry","message":"No factual world progress for 30 seconds; reassessing without pausing the goal.","sequence":5,"at":5}],
       "usage":{"inputTokens":639453,"cachedInputTokens":582144,"outputTokens":2253},"allowance":null,
       "plan":{"steps":[
       {"id":"wood","label":"Wood + stone tools","kind":"milestone","status":"complete","dependsOn":[],"detail":"First tool crafted."},
       {"id":"food","label":"Get food","kind":"inventory","status":"pending","dependsOn":[],"detail":"Current supplies."},
       {"id":"logs","label":"Gather ~12 logs","kind":"inventory","status":"active","dependsOn":["wood"],"detail":"Wood for a base."},
       {"id":"iron","label":"Iron tools/armor, bucket","kind":"inventory","status":"lost","dependsOn":["logs"],"detail":"Required for diamonds."},
       {"id":"portal","label":"Nether portal","kind":"world","status":"pending","dependsOn":["iron"],"detail":"Known infrastructure."},
       {"id":"blaze","label":"Collect blaze rods","kind":"inventory","status":"pending","dependsOn":["portal","food"],"detail":"Current advisory step."},
       {"id":"pearls","label":"Ender pearls","kind":"inventory","status":"pending","dependsOn":["portal"],"detail":"Trade or hunt."},
       {"id":"end","label":"Enter the End and kill the dragon","kind":"milestone","status":"pending","dependsOn":["blaze","pearls"],"detail":"Find the stronghold."}
      ]}}
      """).getAsJsonObject();
    set(outer, "latest", snapshot);
    var viewClass = Class.forName(outer.getName() + "$PlanView");
    var viewConstructor = viewClass.getDeclaredConstructor(graphClass); viewConstructor.setAccessible(true);
    JPanel view = (JPanel) viewConstructor.newInstance(graph);
    setData.invoke(graph, snapshot);
    for (int[] size : new int[][]{{1180, 720}, {760, 460}}) {
     view.setSize(size[0], size[1]); view.doLayout();
     var center = (java.awt.Point) centerOf.invoke(graph, "end");
     if (center == null || center.x < 0 || center.y < 0 || center.x > graph.getWidth() || center.y > graph.getHeight())
      throw new AssertionError("Plan step does not fit inside the window at " + size[0] + "x" + size[1]);
     render(view, output.resolve("plan-" + size[0] + "x" + size[1] + ".png"));
    }
    view.setSize(1180, 720); view.doLayout();
    var center = (java.awt.Point) centerOf.invoke(graph, "iron");
    graph.dispatchEvent(new MouseEvent(graph, MouseEvent.MOUSE_CLICKED, 0, 0, center.x, center.y, 1, false));
    if (!detail(outer).contains("Iron tools/armor")) throw new AssertionError("Graph click did not select the step");
    snapshot.getAsJsonObject("plan").getAsJsonArray("steps").get(3).getAsJsonObject().addProperty("status", "complete");
    setData.invoke(graph, snapshot);
    if (!detail(outer).contains("State: complete")) throw new AssertionError("Selected detail did not refresh after revision");
    render(view, output.resolve("plan-selected.png"));
    snapshot.addProperty("goalRevision", 2); setData.invoke(graph, snapshot);
    if (!detail(outer).startsWith("Select a step")) throw new AssertionError("New task retained the previous selected step");
    var terminalClass = Class.forName(outer.getName() + "$TerminalView");
    var terminalConstructor = terminalClass.getDeclaredConstructor(); terminalConstructor.setAccessible(true);
    JPanel terminal = (JPanel) terminalConstructor.newInstance();
    for (int[] size : new int[][]{{960, 620}, {640, 420}}) {
     terminal.setSize(size[0], size[1]);
     render(terminal, output.resolve("terminal-" + size[0] + "x" + size[1] + ".png"));
    }
    System.out.println("Headless plan and terminal rendering, fit, step selection and task reset passed; images: " + output);
   } catch (Exception exception) { throw new RuntimeException(exception); }
  });
  if(List.of(args).contains("--native")) verifyNative(output);
 }
 private static String detail(Class<?> outer) throws Exception { return (String) call(outer, "detailText"); }
 /** Opt-in on an explicitly authorized GUI machine; uses the same packaged JFrame implementation. */
 private static void verifyNative(Path output) throws Exception {
  if(GraphicsEnvironment.isHeadless())throw new IllegalStateException("--native requires an authorized GUI machine");
  Class<?> windows=Class.forName("dev.agaminggod.arenaagents.client.control.LiveAgentWindows");
  String origin=windows.getProtectionDomain().getCodeSource().getLocation().toURI().toString();
  String expected=System.getProperty("arenaagents.expectedLiveWindowsJar");
  if(expected!=null && !Path.of(windows.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize().equals(Path.of(expected).toAbsolutePath().normalize()))throw new AssertionError("Unexpected live-window class origin: "+origin);
  Path settingsDirectory=Files.createTempDirectory(output,"native-settings-");
  Path settings=settingsDirectory.resolve("arenaagents-live-windows.properties");
  set(windows,"settingsPath",settings);set(windows,"initialized",false);
  AgentControlAgent first=agent(UUID.fromString("11111111-1111-1111-1111-111111111111"),"Window QA One");
  AgentControlAgent second=agent(UUID.fromString("22222222-2222-2222-2222-222222222222"),"Window QA Two");
  int checks=0;
  try {
   check(!(boolean)call(windows,"planEnabled") && !(boolean)call(windows,"terminalEnabled"),"Fresh local toggles default off");checks++;
   call(windows,"togglePlan",AgentControlAgent.class,first);flushEdt();
   JFrame plan=(JFrame)get(windows,"planFrame");check(plan!=null && plan.isShowing(),"Plan toggle opens native frame");checks++;
   call(windows,"toggleTerminal",AgentControlAgent.class,first);flushEdt();
   JFrame terminal=(JFrame)get(windows,"terminalFrame");check(terminal!=null && terminal.isShowing() && get(windows,"planFrame")==plan,"Terminal opens independently");checks++;
   packet(windows,first,1,true,"Connected","First task output");
   check(((String)call(windows,"terminalText")).contains("First task output"),"Live packet populates terminal");checks++;
   SwingUtilities.invokeAndWait(()->plan.dispatchEvent(new WindowEvent(plan,WindowEvent.WINDOW_CLOSING)));
   check(get(windows,"planFrame")==null && get(windows,"terminalFrame")==terminal && terminal.isShowing(),"Plan close keeps terminal open");checks++;
   Properties saved=new Properties();try(var in=Files.newInputStream(settings)){saved.load(in);}
   check(saved.getProperty("plan").equals("false") && saved.getProperty("terminal").equals("true"),"Independent close is persisted");checks++;
   call(windows,"togglePlan",AgentControlAgent.class,first);flushEdt();
   JFrame reopenedPlan=(JFrame)get(windows,"planFrame");check(reopenedPlan!=null && reopenedPlan!=plan && get(windows,"terminalFrame")==terminal,"Closed plan reopens without replacing terminal");checks++;
   SwingUtilities.invokeAndWait(()->terminal.dispatchEvent(new WindowEvent(terminal,WindowEvent.WINDOW_CLOSING)));
   check(get(windows,"terminalFrame")==null && get(windows,"planFrame")==reopenedPlan,"Terminal close keeps plan open");checks++;
   set(windows,"initialized",false);set(windows,"planEnabled",false);set(windows,"terminalEnabled",false);
   check((boolean)call(windows,"planEnabled") && !(boolean)call(windows,"terminalEnabled"),"Preferences reload the last independent toggles");checks++;
   call(windows,"toggleTerminal",AgentControlAgent.class,first);flushEdt();
   call(windows,"select",AgentControlAgent.class,second);flushEdt();
   check(!((String)call(windows,"terminalText")).contains("First task output") && ((JFrame)get(windows,"terminalFrame")).getTitle().contains("Window QA Two"),"Agent switch clears prior output and updates title");checks++;
   packet(windows,first,2,true,"Connected","Late old agent output");
   check(!((String)call(windows,"terminalText")).contains("Late old agent output"),"Late packet for former selection is ignored");checks++;
   packet(windows,second,2,true,"Connected","Second task output");
   packet(windows,second,1,true,"Connected","Old revision output");
   check(!((String)call(windows,"terminalText")).contains("Old revision output"),"Older goal revision cannot replace current display");checks++;
   JFrame currentPlan=(JFrame)get(windows,"planFrame");
   SwingUtilities.invokeAndWait(()->currentPlan.dispatchEvent(new WindowEvent(currentPlan,WindowEvent.WINDOW_CLOSING)));
   set(windows,"receivedAt",System.currentTimeMillis()-5000);
   call(windows,"accept",LiveTaskViewPayload.Snapshot.class,get(windows,"lastSnapshot"));flushEdt();
   Thread.sleep(1200);flushEdt();
   check(((String)call(windows,"terminalText")).contains("Live data is stale"),"Terminal alone refreshes stale state and cached replay cannot hide it");checks++;
   packet(windows,second,2,false,"Coordinator offline; displayed data may be stale","Second task output");
   check(((String)call(windows,"terminalText")).contains("Coordinator offline"),"Offline coordinator is shown");checks++;
   packet(windows,second,3,true,"Connected","Fresh task output");
   String fresh=((String)call(windows,"terminalText"));check(fresh.contains("Fresh task output") && !fresh.contains("Second task output") && !fresh.contains("offline"),"Fresh task reconnect replaces old output");checks++;
   call(windows,"clearView",String.class,"Selected agent is no longer available");flushEdt();
   String removed=((String)call(windows,"terminalText"));check(removed.contains("no longer available") && !removed.contains("Fresh task output"),"Removed selected agent clears terminal content");checks++;
   call(windows,"disconnect");flushEdt();
   check(get(windows,"planFrame")==null && get(windows,"terminalFrame")==null && (boolean)call(windows,"terminalEnabled"),"Minecraft disconnect disposes frames and retains local preference");checks++;
   SwingUtilities.invokeAndWait(()->{try{call(windows,"syncFrames");}catch(Exception e){throw new RuntimeException(e);}});
   packet(windows,second,3,true,"Connected","After reconnect output");
   check(((JFrame)get(windows,"terminalFrame")).isShowing() && ((String)call(windows,"terminalText")).contains("After reconnect output"),"Reconnect frame synchronization restores enabled viewer");checks++;
   String result="PASS: "+checks+" native window lifecycle checks\nLiveAgentWindows origin: "+origin+"\nSettings: isolated temporary directory\nScope: actual JFrame implementation; Minecraft Manage widgets and server networking are not launched\n";
   Files.writeString(output.resolve("native-verification.txt"),result);System.out.print(result);
  } catch(Throwable failure) {
   Files.writeString(output.resolve("native-verification.txt"),"FAIL after "+checks+" checks\nLiveAgentWindows origin: "+origin+"\n"+failure+"\n");throw failure;
  } finally {
   call(windows,"disconnect");flushEdt();Files.deleteIfExists(settings);Files.deleteIfExists(settingsDirectory);
  }
 }
 private static AgentControlAgent agent(UUID id,String name){return new AgentControlAgent(id.toString(),id.toString().substring(0,8),name,"codex","gpt-6.1-sol","medium","WindowQA",0,"PLANNING","Window QA task",0,"","",true,true);}
 private static void packet(Class<?> windows,AgentControlAgent agent,long revision,boolean online,String status,String message)throws Exception{
  var root=JsonParser.parseString("""
   {"goalRevision":0,"goal":"Window QA task","active":true,"verified":false,"revision":1,"generatedAt":0,"plan":null,"lastObserved":{},"events":[{"stage":"live_tool","message":"","sequence":1,"at":1000}],"usage":null,"allowance":null}
   """).getAsJsonObject();root.addProperty("goalRevision",revision);root.addProperty("generatedAt",System.currentTimeMillis());root.getAsJsonArray("events").get(0).getAsJsonObject().addProperty("message",message);
  call(windows,"accept",LiveTaskViewPayload.Snapshot.class,new LiveTaskViewPayload.Snapshot(UUID.fromString(agent.agentId()),online,status,root.toString()));flushEdt();
 }
 private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);}
 private static void flushEdt()throws Exception{SwingUtilities.invokeAndWait(()->{});}
 private static Object get(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 private static void set(Class<?> type,String name,Object value)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
 private static Object call(Class<?> type,String name)throws Exception{var m=type.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(null);}
 private static Object call(Class<?> type,String name,Class<?> parameter,Object value)throws Exception{var m=type.getDeclaredMethod(name,parameter);m.setAccessible(true);return m.invoke(null,value);}
 private static void render(JPanel panel, Path output) throws Exception {
  BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
  var graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose();
  ImageIO.write(image, "png", output.toFile());
 }
}
