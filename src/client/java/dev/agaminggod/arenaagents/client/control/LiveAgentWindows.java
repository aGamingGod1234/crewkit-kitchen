package dev.agaminggod.arenaagents.client.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.control.AgentControlAgent;
import dev.agaminggod.arenaagents.control.LiveTaskViewData;
import dev.agaminggod.arenaagents.control.LiveTaskViewPayload;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.CubicCurve2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Independent, read-only desktop views of the selected bot. Swing owns its widgets. */
public final class LiveAgentWindows {
 private static final Logger LOGGER=LoggerFactory.getLogger(LiveAgentWindows.class);
 private static final Color BACK=new Color(0x101719), PANEL=new Color(0x172124), LINE=new Color(0x34484d), TEXT=new Color(0xecf1ef), MUTED=new Color(0xa5b8b7), GREEN=new Color(0x7bd9a4), YELLOW=new Color(0xf5d270);
 private static volatile boolean planEnabled, terminalEnabled;
 private static volatile String selected="", displayName="Agent";
 private static boolean initialized, wasConnected;
 private static int pollTicks;
 private static volatile long receivedAt;
 private static JFrame planFrame, terminalFrame;
 private static JLabel goalLabel, statusLabel, terminalStatus;
 private static JTextArea detail, terminal;
 private static Graph graph;
 private static JScrollPane graphScroll;
 private static JsonObject latest=new JsonObject(); // EDT only
 private static String lastTerminal="";
 private static LiveTaskViewPayload.Snapshot lastSnapshot;
 private static volatile String waitingStatus="Waiting for live task data";
 private static Path settingsPath;
 private static Timer refreshTimer;
 private LiveAgentWindows() { }

 private static synchronized void initialize() {
  if(initialized) return; initialized=true;
  try {
   Path file=settingsFile(); Properties p=new Properties();
   if(Files.isRegularFile(file) && Files.size(file)<4096) try(var in=Files.newInputStream(file)) { p.load(in); }
   planEnabled=Boolean.parseBoolean(p.getProperty("plan","false")); terminalEnabled=Boolean.parseBoolean(p.getProperty("terminal","false"));
   String id=p.getProperty("agent",""); if(!id.isBlank()) selected=UUID.fromString(id).toString();
  } catch(Exception e) { LOGGER.warn("Could not read live window settings",e); }
 }
 private static Path settingsFile() {
  if(settingsPath==null) settingsPath=FabricLoader.getInstance().getConfigDir().resolve("arenaagents-live-windows.properties");
  return settingsPath;
 }
 private static synchronized void save() {
  try {
   Properties p=new Properties(); p.setProperty("plan",String.valueOf(planEnabled)); p.setProperty("terminal",String.valueOf(terminalEnabled)); p.setProperty("agent",selected);
   Path file=settingsFile(); Files.createDirectories(file.getParent()); try(var out=Files.newOutputStream(file)) { p.store(out,"Arena Agents local display settings"); }
  } catch(Exception e) { LOGGER.warn("Could not save live window settings",e); }
 }
 public static boolean planEnabled() { initialize(); return planEnabled; }
 public static boolean terminalEnabled() { initialize(); return terminalEnabled; }
 public static void select(AgentControlAgent agent) {
  initialize(); String next=agent==null?"":agent.agentId();
  displayName=agent==null?"Agent":agent.displayName();
  if(!selected.equals(next)) {
   selected=next; receivedAt=0; pollTicks=0; save();
   clearView(agent==null?"No agent selected":"Waiting for live task data");
  }
 }
 private static void clearView(String status) {
  PlanItemIcons.clear();
  waitingStatus=status; receivedAt=0;
  SwingUtilities.invokeLater(()->{ latest=new JsonObject(); lastSnapshot=null; lastTerminal=""; refresh(); });
 }
 public static boolean togglePlan(AgentControlAgent agent) { select(agent); planEnabled=!planEnabled; return applySettings(); }
 public static boolean toggleTerminal(AgentControlAgent agent) { select(agent); terminalEnabled=!terminalEnabled; return applySettings(); }
 private static boolean applySettings() {
  if(GraphicsEnvironment.isHeadless()) { planEnabled=false; terminalEnabled=false; save(); return false; }
  save(); SwingUtilities.invokeLater(LiveAgentWindows::syncFrames); return true;
 }
 public static void tick(Minecraft client) {
  initialize(); boolean connected=client.player!=null && client.level!=null && client.getConnection()!=null;
  if(!connected) { if(wasConnected) disconnect(); return; }
  if(!wasConnected) { wasConnected=true; pollTicks=0; }
  if(!planEnabled && !terminalEnabled || selected.isBlank()) return;
  var roster=AgentControlClient.snapshot().orElse(null);
  if(roster==null) return;
  if(!roster.canControl()) { if(!waitingStatus.equals("Operator permission is required")) clearView("Operator permission is required"); SwingUtilities.invokeLater(LiveAgentWindows::disposeFrames); return; }
  var agent=roster.agents().stream().filter(a->a.agentId().equals(selected)).findFirst().orElse(null);
  if(agent==null) { if(!waitingStatus.equals("Selected agent is no longer available")) clearView("Selected agent is no longer available"); return; }
  displayName=agent.displayName();
  if(--pollTicks>0) return; pollTicks=10;
  if(!GraphicsEnvironment.isHeadless()) SwingUtilities.invokeLater(LiveAgentWindows::syncFrames);
  if(ClientPlayNetworking.canSend(LiveTaskViewPayload.Request.TYPE)) ClientPlayNetworking.send(new LiveTaskViewPayload.Request(UUID.fromString(selected)));
 }
 public static void accept(LiveTaskViewPayload.Snapshot packet) {
  if(!packet.agentId().toString().equals(selected)) return;
  final JsonObject parsed;
  try { parsed=LiveTaskViewData.parse(packet.json()); } catch(RuntimeException e) { LOGGER.warn("Rejected invalid live task display data",e); return; }
  SwingUtilities.invokeLater(()->{
   if(!packet.agentId().toString().equals(selected)) return;
   if(parsed.size()>0 && latest.size()>0) {
    long revision=parsed.get("goalRevision").getAsLong(), current=latest.get("goalRevision").getAsLong();
    if(revision<current || revision==current && parsed.get("generatedAt").getAsLong()<latest.get("generatedAt").getAsLong()) return;
   }
   // The server replays its cache while awaiting the coordinator; replay is not fresh coordinator data.
   if(lastSnapshot==null || !parsed.equals(latest)) receivedAt=System.currentTimeMillis();
   waitingStatus="Waiting for live task data";
   latest=parsed; lastSnapshot=packet; refresh();
  });
 }
 public static void disconnect() {
  PlanItemIcons.clear();
  wasConnected=false; receivedAt=0; pollTicks=0;
  waitingStatus="Disconnected from Minecraft";
  SwingUtilities.invokeLater(()->{ latest=new JsonObject(); lastSnapshot=null; lastTerminal=""; disposeFrames(); });
 }
 private static void disposeFrames() {
  if(planFrame!=null) planFrame.dispose(); if(terminalFrame!=null) terminalFrame.dispose();
  planFrame=null; terminalFrame=null; graph=null; terminal=null; goalLabel=null; statusLabel=null; terminalStatus=null;
  detail=null; graphScroll=null;
  if(refreshTimer!=null) refreshTimer.stop();
 }
 private static void syncFrames() {
  if(planEnabled && planFrame==null) createPlan();
  if(!planEnabled && planFrame!=null) { planFrame.dispose(); planFrame=null; graph=null; goalLabel=null; statusLabel=null; detail=null; graphScroll=null; PlanItemIcons.clear(); }
  if(terminalEnabled && terminalFrame==null) createTerminal();
  if(!terminalEnabled && terminalFrame!=null) { terminalFrame.dispose(); terminalFrame=null; terminal=null; terminalStatus=null; }
  if(refreshTimer==null) refreshTimer=new Timer(1000,e->refresh());
  if(planFrame!=null || terminalFrame!=null) { if(!refreshTimer.isRunning()) refreshTimer.start(); }
  else refreshTimer.stop();
  refresh();
 }
 private static JFrame frame(String name,int width,int height,boolean plan) {
  JFrame f=new JFrame(name); f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); f.setSize(width,height); f.setLocationByPlatform(true); f.setAutoRequestFocus(false); f.getContentPane().setBackground(BACK);
  f.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){
   if((plan?planFrame:terminalFrame)!=f) return;
   if(plan) planEnabled=false; else terminalEnabled=false;
   save(); syncFrames();
  }});
  return f;
 }
 private static JTextArea area() {
  JTextArea a=new JTextArea(); a.setEditable(false); a.setLineWrap(true); a.setWrapStyleWord(true); a.setBackground(BACK); a.setForeground(TEXT); a.setCaretColor(TEXT); a.setBorder(BorderFactory.createEmptyBorder(16,16,16,16)); a.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,14)); return a;
 }
 private static JLabel label(String text) { JLabel l=new JLabel(text); l.setForeground(TEXT); return l; }
 private static void createPlan() {
  planFrame=frame("Agent plan | "+displayName,1180,760,true);
  JPanel head=new JPanel(new BorderLayout(12,8)); head.setBackground(PANEL); head.setBorder(BorderFactory.createEmptyBorder(16,20,16,20));
  goalLabel=label("Waiting for the selected agent's plan"); goalLabel.setFont(new Font(Font.SANS_SERIF,Font.BOLD,18)); statusLabel=label("Connecting"); statusLabel.setForeground(MUTED);
  JPanel headings=new JPanel(new BorderLayout(0,8)); headings.setOpaque(false); headings.add(goalLabel,BorderLayout.NORTH); headings.add(statusLabel,BorderLayout.SOUTH); head.add(headings,BorderLayout.CENTER);
  JPanel zoom=new JPanel(); zoom.setOpaque(false);
  for(String title:List.of("−","+","Fit")) { JButton b=new JButton(title); b.addActionListener(e->{if(graph==null)return; graph.zoom=title.equals("Fit")?Math.max(.25,Math.min(1,(graphScroll.getViewport().getWidth()-40)/(double)graph.logicalWidth)):Math.max(.25,Math.min(2,graph.zoom*(title.equals("+")?1.2:1/1.2))); graph.resizeGraph();}); zoom.add(b); }
  head.add(zoom,BorderLayout.EAST); planFrame.add(head,BorderLayout.NORTH);
  detail=area(); detail.setText("Select a step to see its evidence and persistence rules.");
  graph=new Graph(); graphScroll=new JScrollPane(graph); graphScroll.getViewport().setBackground(BACK); graphScroll.setBorder(BorderFactory.createEmptyBorder());
  JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,graphScroll,new JScrollPane(detail)); split.setResizeWeight(.76); split.setDividerLocation(870); split.setBorder(BorderFactory.createEmptyBorder()); planFrame.add(split,BorderLayout.CENTER);
  JLabel legend=label("  Green: complete / known     Yellow: active / lost     Grey: pending     Plan is advisory; the agent owns decisions."); legend.setBorder(BorderFactory.createEmptyBorder(10,6,10,6)); planFrame.add(legend,BorderLayout.SOUTH);
  planFrame.setVisible(true);
 }
 private static void createTerminal() {
  terminalFrame=frame("Codex live terminal | "+displayName,950,620,false); terminal=area(); terminal.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13)); terminal.setLineWrap(false);
  terminalFrame.add(new JScrollPane(terminal),BorderLayout.CENTER); terminalStatus=label("  Existing Codex run; read-only output, tools and available summaries"); terminalStatus.setBorder(BorderFactory.createEmptyBorder(12,6,12,6)); terminalFrame.add(terminalStatus,BorderLayout.SOUTH); terminalFrame.setVisible(true); lastTerminal="";
 }
 private static void refresh() {
  String state=lastSnapshot==null?waitingStatus:lastSnapshot.status();
  if(lastSnapshot!=null && lastSnapshot.online() && latest.size()>0 && receivedAt>0 && System.currentTimeMillis()-receivedAt>4000) state="Live data is stale; waiting for coordinator";
  if(planFrame!=null) {
   planFrame.setTitle("Agent plan | "+displayName); goalLabel.setText((latest.has("verified")&&latest.get("verified").getAsBoolean()?"Verified goal: ":latest.has("active")&&!latest.get("active").getAsBoolean()?"Last task: ":"Goal: ")+string(latest,"goal","No detailed plan yet"));
   statusLabel.setText(state+" · Revision "+string(latest,"revision","0")); graph.setData(latest);
  }
  if(terminalFrame!=null) {
   terminalFrame.setTitle("Codex live terminal | "+displayName);
   StringBuilder text=new StringBuilder("Existing Codex task | "+displayName+"\n"+state+"\n\n");
   if(latest.has("events")) for(var value:latest.getAsJsonArray("events")) { var e=value.getAsJsonObject(); text.append('[').append(string(e,"stage","event").replace("live_","")).append("] ").append(string(e,"message","")).append('\n'); }
   if(!text.toString().equals(lastTerminal)) {
    boolean following=terminal.getCaretPosition()>=Math.max(0,terminal.getDocument().getLength()-1);
    lastTerminal=text.toString(); terminal.setText(lastTerminal); if(following) terminal.setCaretPosition(terminal.getDocument().getLength());
   }
   terminalStatus.setText(usageText(latest));
  }
 }
 private static String usageText(JsonObject root) {
  List<String> lines=new ArrayList<>();
  if(root.has("usage") && !root.get("usage").isJsonNull()) {
   var u=root.getAsJsonObject("usage");
   String uncached=tokenNumber(u,"uncachedInputTokens");
   if(uncached.equals("?") && hasToken(u,"inputTokens") && hasToken(u,"cachedInputTokens") && u.get("cachedInputTokens").getAsLong()<=u.get("inputTokens").getAsLong()) uncached=tokenNumber(u.get("inputTokens").getAsLong()-u.get("cachedInputTokens").getAsLong());
   lines.add("Thread totals · Input "+tokenNumber(u,"inputTokens")+" (includes cache) · Uncached "+uncached+" · Cached "+cachedTokens(u,"inputTokens","cachedInputTokens")+" · Output "+tokenNumber(u,"outputTokens"));
   if(hasToken(u,"lastInputTokens")) lines.add("Latest model input "+tokenNumber(u,"lastInputTokens")+" (includes cache) · Uncached "+tokenNumber(u,"lastUncachedInputTokens")+" · Cached "+cachedTokens(u,"lastInputTokens","lastCachedInputTokens")+" · Output "+tokenNumber(u,"lastOutputTokens"));
   else if(hasToken(u,"intervalInputTokens")) lines.add("Between usage updates · Input "+tokenNumber(u,"intervalInputTokens")+" (includes cache) · Uncached "+tokenNumber(u,"intervalUncachedInputTokens")+" · Cached "+tokenNumber(u,"intervalCachedInputTokens")+" · Output "+tokenNumber(u,"intervalOutputTokens"));
   if(hasToken(u,"observedElapsedMs") && u.get("observedElapsedMs").getAsLong()>0 && (hasToken(u,"inputTokensPerMinute") || hasToken(u,"outputTokensPerMinute"))) lines.add("Tokens/min (last "+String.format(Locale.ROOT,"%.1f",u.get("observedElapsedMs").getAsLong()/1000d)+" s sample) · Input "+tokenNumber(u,"inputTokensPerMinute")+" · Uncached "+tokenNumber(u,"uncachedInputTokensPerMinute")+" · Cached "+tokenNumber(u,"cachedInputTokensPerMinute")+" · Output "+tokenNumber(u,"outputTokensPerMinute"));
   else lines.add("Rate pending two timed usage updates");
  } else lines.add("Usage unavailable");
  String allowanceText="";
  if(root.has("allowance") && !root.get("allowance").isJsonNull()) { var a=root.getAsJsonObject("allowance"); for(String k:List.of("primary","secondary")) if(a.has(k)) {var w=a.getAsJsonObject(k);allowanceText+=(allowanceText.isEmpty()?"":" · ")+"Shared "+(hasToken(w,"windowDurationMins")?tokenNumber(w,"windowDurationMins")+" min":k)+": "+String.format(Locale.ROOT,"%.1f",Math.max(0,100-w.get("usedPercent").getAsDouble()))+"% left";} }
  lines.add((allowanceText.isEmpty()?"":allowanceText+" · ")+"Recent events; long payloads may be truncated");
  return "<html>"+String.join("<br>",lines)+"</html>";
 }
 private static boolean hasToken(JsonObject object,String key) { var value=object.get(key);return value!=null && !value.isJsonNull(); }
 private static String tokenNumber(JsonObject object,String key) { return hasToken(object,key)?tokenNumber(object.get(key).getAsLong()):"?"; }
 private static String tokenNumber(long value) { return String.format(Locale.ROOT,"%,d",value); }
 private static String cachedTokens(JsonObject usage,String inputKey,String cachedKey) {
  String result=tokenNumber(usage,cachedKey);
  if(hasToken(usage,inputKey) && hasToken(usage,cachedKey)) { long input=usage.get(inputKey).getAsLong(),cached=usage.get(cachedKey).getAsLong(); if(input>0 && cached<=input) result+=" ("+String.format(Locale.ROOT,"%.1f",cached/(double)input*100)+"%)"; }
  return result;
 }
 private static String string(JsonObject o,String key,String fallback) { var e=o.get(key);return e==null||e.isJsonNull()?fallback:e.getAsString(); }
 static void iconsChanged() { SwingUtilities.invokeLater(()->{if(graph!=null)graph.repaint();}); }
 private static boolean complete(JsonObject s) { return string(s,"status","").equals("complete"); }
 private static Color nodeColor(JsonObject s) { String status=string(s,"status",""); return status.equals("complete")?GREEN:status.equals("active")||status.equals("lost")?YELLOW:MUTED; }

 private static final class Graph extends JPanel {
  private final Map<String,JsonObject> steps=new LinkedHashMap<>();
  private final Map<String,Point> positions=new HashMap<>();
  private String selectedStep="", signature=null, taskIdentity="";
  private double zoom=1;
  private int logicalWidth=800,logicalHeight=500;
  Graph() {
   setBackground(BACK); setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13));
   resizeGraph();
   addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){
    double x=e.getX()/zoom,y=e.getY()/zoom;
    for(var entry:positions.entrySet()) {Point p=entry.getValue();if(x>=p.x&&x<p.x+194&&y>=p.y&&y<p.y+94){selectedStep=entry.getKey();showDetail();repaint();break;}}
   }});
  }
  void setData(JsonObject root) {
   String identity=string(root,"goalRevision","")+":"+string(root,"goal","");
   if(!identity.equals(taskIdentity)) {taskIdentity=identity;selectedStep="";}
   String next=root.has("plan")?root.get("plan").toString():"";
   if(next.equals(signature)) {showDetail();return;} signature=next; steps.clear(); positions.clear();
   if(root.has("plan") && !root.get("plan").isJsonNull()) for(var v:root.getAsJsonObject("plan").getAsJsonArray("steps")) {var s=v.getAsJsonObject();steps.put(s.get("id").getAsString(),s);}
   PlanItemIcons.retain(steps.values().stream().map(PlanItemIcons::itemId).collect(java.util.stream.Collectors.toSet()));
   Map<String,Integer> levels=new HashMap<>();
   for(int pass=0;pass<steps.size();pass++) for(var entry:steps.entrySet()) {
    if(levels.containsKey(entry.getKey()))continue;int level=0;boolean ready=true;
    for(var dependency:entry.getValue().getAsJsonArray("dependsOn")){Integer parent=levels.get(dependency.getAsString());if(parent==null){ready=false;break;}level=Math.max(level,parent+1);}
    if(ready)levels.put(entry.getKey(),level);
   }
   Map<Integer,Integer> rows=new HashMap<>();int maxColumn=0,maxRow=0;
   for(var entry:steps.entrySet()) {int column=levels.getOrDefault(entry.getKey(),0),row=rows.getOrDefault(column,0);rows.put(column,row+1);positions.put(entry.getKey(),new Point(28+column*236,34+row*138));maxColumn=Math.max(maxColumn,column);maxRow=Math.max(maxRow,row);}
   logicalWidth=Math.max(800,260+maxColumn*236);logicalHeight=Math.max(500,170+maxRow*138);resizeGraph();showDetail();
  }
  void resizeGraph(){setPreferredSize(new Dimension((int)(logicalWidth*zoom),(int)(logicalHeight*zoom)));revalidate();repaint();}
  void showDetail(){var s=steps.get(selectedStep);if(s==null){setDetail(steps.isEmpty()?"No detailed plan available yet. The main agent can publish one with taskPlan. The final goal is still tracked independently.":"Select a step.\n\nInventory: current possessions\nWorld: remembered structure\nMilestone: historical progress\nManual: agent-reported plan step");return;}
   String kind=string(s,"kind",""); String freshness="";
   if(kind.equals("world")&&latest.has("lastObserved")){var seen=latest.getAsJsonObject("lastObserved").get(selectedStep);if(seen!=null&&!seen.isJsonNull())freshness="\nLast observed: "+java.time.Instant.ofEpochMilli(seen.getAsLong()).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();}
   setDetail(string(s,"label","")+"\n\nState: "+string(s,"status","")+"\nEvidence: "+kind+freshness+"\n\n"+string(s,"detail","")+"\n\n"+(kind.equals("inventory")?"Rechecked against current inventory. Death can invalidate this requirement.":kind.equals("world")?"Retained after death. Known from an observation; recheck when relying on it.":"Agent-reported advisory progress. Final goal verification is separate."));
  }
  private static void setDetail(String text){if(detail!=null && !detail.getText().equals(text)){detail.setText(text);detail.setCaretPosition(0);}}
  @Override protected void paintComponent(Graphics original) {
   super.paintComponent(original);Graphics2D g=(Graphics2D)original.create();g.scale(zoom,zoom);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setStroke(new BasicStroke(2));
   if(steps.isEmpty()){g.setColor(MUTED);g.drawString("Waiting for an agent-authored dependency plan",30,60);g.dispose();return;}
   for(var entry:steps.entrySet())for(var dependency:entry.getValue().getAsJsonArray("dependsOn")){
    Point from=positions.get(dependency.getAsString()),to=positions.get(entry.getKey());if(from==null||to==null)continue;
    g.setColor(complete(entry.getValue())&&complete(steps.get(dependency.getAsString()))?new Color(0x4f8b68):LINE);
    int x1=from.x+194,y1=from.y+47,x2=to.x,y2=to.y+47;g.draw(new CubicCurve2D.Double(x1,y1,x1+26,y1,x2-26,y2,x2,y2));g.fillPolygon(new int[]{x2,x2-7,x2-7},new int[]{y2,y2-4,y2+4},3);
   }
   for(var entry:steps.entrySet()){
    var s=entry.getValue();Point p=positions.get(entry.getKey());Color color=nodeColor(s);g.setColor(complete(s)?new Color(0x1b2b24):color.equals(YELLOW)?new Color(0x332e20):PANEL);g.fillRoundRect(p.x,p.y,194,94,12,12);g.setColor(entry.getKey().equals(selectedStep)?new Color(0x83cbd0):color);g.drawRoundRect(p.x,p.y,194,94,12,12);
    var icon=PlanItemIcons.image(s);if(icon!=null){g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);g.drawImage(icon,p.x+10,p.y+14,28,28,null);}g.setColor(TEXT);g.setFont(getFont().deriveFont(Font.BOLD));
    String title=string(s,"label",""); List<String> words=wrap(g,title,145);for(int i=0;i<Math.min(2,words.size());i++)g.drawString(words.get(i),p.x+40,p.y+24+i*17);
    g.setFont(getFont().deriveFont(11f));g.setColor(color);String kind=string(s,"kind","");String status=string(s,"status","");g.drawString(status.equals("complete")?(kind.equals("world")?"Known · last observed":kind.equals("inventory")?"Present in inventory":"Complete · agent reported"):status.equals("lost")?"Lost · recovery needed":status.equals("active")?"In progress":"Pending",p.x+12,p.y+77);
   }
   g.dispose();
  }
  private static List<String> wrap(Graphics2D g,String title,int width){List<String> lines=new ArrayList<>();String line="";for(String word:title.split(" ")){String next=line.isEmpty()?word:line+" "+word;if(!line.isEmpty()&&g.getFontMetrics().stringWidth(next)>width){lines.add(line);line=word;}else line=next;}if(!line.isEmpty())lines.add(line);return lines;}
 }
}
