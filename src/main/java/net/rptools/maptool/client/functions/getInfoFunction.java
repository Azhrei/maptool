/*
 * This software Copyright by the RPTools.net development team, and
 * licensed under the Affero GPL Version 3 or, at your option, any later
 * version.
 *
 * MapTool Source Code is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public
 * License * along with this source Code.  If not, please visit
 * <http://www.gnu.org/licenses/> and specifically the Affero license
 * text at <http://www.gnu.org/licenses/agpl.html>.
 */
package net.rptools.maptool.client.functions;

import com.google.common.collect.Streams;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.rptools.maptool.client.AppPreferences;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.client.MapToolExpressionParser;
import net.rptools.maptool.client.ui.htmlframe.HTMLDialog;
import net.rptools.maptool.client.ui.htmlframe.HTMLFrame;
import net.rptools.maptool.client.ui.htmlframe.HTMLOverlayManager;
import net.rptools.maptool.client.ui.token.*;
import net.rptools.maptool.language.I18N;
import net.rptools.maptool.model.*;
import net.rptools.maptool.model.drawing.DrawableColorPaint;
import net.rptools.maptool.model.drawing.DrawablePaint;
import net.rptools.maptool.model.drawing.DrawableTexturePaint;
import net.rptools.maptool.server.ServerPolicy;
import net.rptools.maptool.util.FunctionUtil;
import net.rptools.maptool.util.MapToolSysInfoProvider;
import net.rptools.maptool.util.SysInfoProvider;
import net.rptools.parser.Parser;
import net.rptools.parser.ParserException;
import net.rptools.parser.VariableResolver;
import net.rptools.parser.function.AbstractFunction;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;

public class getInfoFunction extends AbstractFunction {

  /** The singleton instance. */
  private static final getInfoFunction instance = new getInfoFunction();

  private SysInfoProvider sysInfoProvider;

  private getInfoFunction() {
    super(0, 1, "getInfo");
    sysInfoProvider = new MapToolSysInfoProvider();
  }

  // region the following is here mostly for testing purpose, until we find a better way to inject
  protected void setSysInfoProvider(SysInfoProvider sysInfoProvider) {
    this.sysInfoProvider = sysInfoProvider;
  }

  protected void resetSysInfoProvider() {
    sysInfoProvider = new MapToolSysInfoProvider();
  }

  // endregion

  /**
   * Gets the instance of getInfoFunction.
   *
   * @return the instance.
   */
  public static getInfoFunction getInstance() {
    return instance;
  }

  private final Map<String, MethodHandle> subfuncs = new HashMap<>();

  static {
    MethodHandles.Lookup lookup = MethodHandles.lookup();
    // mt is ()getInfoFunction
    MethodType mt = MethodType.methodType(getInfoFunction.class);
    try {
      mh = lookup.findVirtual(getInfoFunction.class, "getMapInfo", mt);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
    Map<String, String> func_list = Map.ofEntries(
      new AbstractMap.SimpleEntry<String, String>("map", "getMapInfo"),
      new AbstractMap.SimpleEntry<String, String>("zone", "getMapInfo"), // old
      new AbstractMap.SimpleEntry<String, String>("server", "getServerInfo"),
      new AbstractMap.SimpleEntry<String, String>("client", "getClientInfo"),
      new AbstractMap.SimpleEntry<String, String>("functions", "getFunctionLists"),
      new AbstractMap.SimpleEntry<String, String>("campaign", "getCampaignInfo"),
      new AbstractMap.SimpleEntry<String, String>("theme", "getThemeInfo"),
      new AbstractMap.SimpleEntry<String, String>("themelist", "getThemeList"),
      new AbstractMap.SimpleEntry<String, String>("debug", "getDebugInfo")
    );
    for (Map.Entry<String, String> entry : func_list.entrySet()) {
      try {
        MethodHandle mh = lookup.findVirtual(getInfoFunction.class, entry.getValue(), mt);
        instance.subfuncs.put(entry.getKey(), mh);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public Object childEvaluate(
      Parser parser, VariableResolver resolver, String functionName, List<Object> param)
      throws ParserException {
    String infoType = param.getFirst().toString();

    if (param.isEmpty()) {
      // return a list of all possible parameter values for arg1
      JsonArray arr = new JsonArray(instance.subfuncs.size());
      Streams.stream(instance.subfuncs.keySet().stream().iterator())
              .sorted()
              .forEach(arr::add);
      return arr;
    }
    if (param.getFirst() instanceof String firstParam) {
      MethodHandle mh = instance.subfuncs.get(firstParam);
      try {
          JsonObject obj = (JsonObject) mh.invoke();
          return obj;
      } catch (Throwable e) {
          throw new RuntimeException(e);
      }
    } else {
      throw new ParserException(
          I18N.getText("macro.function.getInfo.invalidArg", param.getFirst().toString()));
    }
  }

  /**
   * Retrieves a list of all built in and user-defined functions, returning
   * each in a property of a single JsonObject.
   *
   * @return
   */
  private JsonObject getFunctionLists() {
    UserDefinedMacroFunctions UDF = UserDefinedMacroFunctions.getInstance();
    JsonObject udfList = new JsonObject();
    for (String name : UDF.getAliases()) {
      udfList.addProperty(name, UDF.getFunctionLocation(name));
    }
    JsonArray fList = new JsonArray();
    MapToolExpressionParser.getMacroFunctions()
        .forEach(function -> Arrays.stream(function.getAliases()).forEach(fList::add));

    JsonObject fInfo = new JsonObject();
    fInfo.add("functions", fList);
    fInfo.add("user defined functions", udfList);
    return fInfo;
  }

  /**
   * Retrieves the information about the current zone/map and returns it as a JSON Object.
   *
   * @return The information about the map.
   * @throws ParserException when there is an error.
   */
  private JsonObject getMapInfo() throws ParserException {
    JsonObject minfo = new JsonObject();
    Zone zone = MapTool.getFrame().getCurrentZoneRenderer().getZone();

    if (!MapTool.getParser().isMacroTrusted() && !zone.isVisible()) {
      throw new ParserException(I18N.getText("macro.function.general.noPerm", "getInfo('map')"));
    }

    minfo.addProperty("name", zone.getName());
    minfo.addProperty("display name", zone.getDisplayName());
    minfo.addProperty("image x scale", zone.getImageScaleX());
    minfo.addProperty("image y scale", zone.getImageScaleY());
    minfo.addProperty("player visible", zone.isVisible() ? 1 : 0);

    if (MapTool.getParser().isMacroTrusted()) {
      minfo.addProperty("id", zone.getId().toString());
      minfo.addProperty("creation time", zone.getCreationTime());
      minfo.addProperty("width", zone.getWidth());
      minfo.addProperty("height", zone.getHeight());
      minfo.addProperty("largest Z order", zone.getLargestZOrder());
    }

    String visionType = zone.getVisionType().name();
    minfo.addProperty("vision type", visionType);
    minfo.addProperty("vision distance", zone.getTokenVisionDistance());
    minfo.addProperty("lighting style", zone.getLightingStyle().name());
    minfo.addProperty("has fog", zone.hasFog());
    minfo.addProperty("ai rounding", zone.getAStarRounding().name());

    JsonObject ginfo = new JsonObject();
    Grid grid = zone.getGrid();
    ginfo.addProperty("type", GridFactory.getGridType(grid));
    ginfo.addProperty("color", String.format("%h", zone.getGridColor()));
    ginfo.addProperty("units per cell", zone.getUnitsPerCell());
    ginfo.addProperty("cell height", zone.getGrid().getCellHeight());
    ginfo.addProperty("cell width", zone.getGrid().getCellWidth());
    ginfo.addProperty("cell offset width", zone.getGrid().getCellOffset().getWidth());
    ginfo.addProperty("cell offset height", zone.getGrid().getCellOffset().getHeight());
    ginfo.addProperty("size", zone.getGrid().getSize());
    ginfo.addProperty("x offset", zone.getGrid().getOffsetX());
    ginfo.addProperty("y offset", zone.getGrid().getOffsetY());
    ginfo.addProperty("second dimension", grid.getSecondDimension());
    minfo.add("grid", ginfo);

    String background = getBackground(zone.getBackgroundPaint());
    minfo.addProperty("background paint", background);

    background = getBackground(zone.getFogPaint());
    minfo.addProperty("fog paint", background);

    final var mapAsset = zone.getMapAssetId();
    minfo.addProperty("map asset", mapAsset == null ? null : "asset://" + mapAsset.toString());

    return minfo;
  }

  @Nullable
  private static String getBackground(DrawablePaint drawable) {
    String background = null;
    if (drawable instanceof DrawableColorPaint dcp) {
      background = String.format("#%h", dcp.getColor());
    } else if (drawable instanceof DrawableTexturePaint dtp) {
      background = "asset://" + dtp.getAssetId().toString();
    }
    return background;
  }

  /**
   * Retrieves the client side preferences that do not have server over rides as a json object.
   *
   * @return the client side preferences
   */
  private JsonObject getClientInfo() {
    JsonObject cinfo = new JsonObject();

    cinfo.addProperty("face edge", FunctionUtil.getDecimalForBoolean(AppPreferences.getFaceEdge()));
    cinfo.addProperty(
        "face vertex", FunctionUtil.getDecimalForBoolean(AppPreferences.getFaceVertex()));
    cinfo.addProperty("portrait size", AppPreferences.getPortraitSize());
    cinfo.addProperty("show portrait", AppPreferences.getShowPortrait());
    cinfo.addProperty("show stat sheet", AppPreferences.getShowStatSheet());
    cinfo.addProperty("file sync directory", AppPreferences.getFileSyncPath());
    cinfo.addProperty("show avatar in chat", AppPreferences.getShowAvatarInChat());
    cinfo.addProperty(
        "suppress tooltips for macroLinks", AppPreferences.getSuppressToolTipsForMacroLinks());
    cinfo.addProperty("use tooltips for inline rolls", AppPreferences.getUseToolTipForInlineRoll());
    cinfo.addProperty("version", MapTool.getVersion());
    cinfo.addProperty(
        "isFullScreen", FunctionUtil.getDecimalForBoolean(MapTool.getFrame().isFullScreen()));
    cinfo.addProperty("timeInMs", System.currentTimeMillis());
    cinfo.addProperty("timeDate", getTimeDate());
    cinfo.addProperty("isoTimeDate", getIsoTimeDate());
    cinfo.addProperty("isHosting", MapTool.isHostingServer());
    cinfo.addProperty("isPersonalServer", MapTool.isPersonalServer());
    cinfo.addProperty("userLanguage", MapTool.getLanguage());

    JsonObject dialogs = new JsonObject();
    Set<String> dialogNames = HTMLDialog.getDialogNames();
    for (String name : dialogNames) {
      Optional<JsonObject> props = HTMLDialog.getDialogProperties(name);
      props.ifPresent(jsonObject -> dialogs.add(name, jsonObject));
    }
    cinfo.add("dialogs", dialogs);

    JsonObject frames = new JsonObject();
    Set<String> frameNames = HTMLFrame.getFrameNames();
    for (String name : frameNames) {
      Optional<JsonObject> props = HTMLFrame.getFrameProperties(name);
      props.ifPresent(jsonObject -> frames.add(name, jsonObject));
    }
    cinfo.add("frames", frames);

    JsonObject overlays = new JsonObject();
    ConcurrentSkipListSet<HTMLOverlayManager> registeredOverlays =
        MapTool.getFrame().getOverlayPanel().getOverlays();
    for (HTMLOverlayManager o : registeredOverlays) {
      overlays.add(o.getName(), o.getProperties());
    }
    cinfo.add("overlays", overlays);

    if (MapTool.getParser().isMacroTrusted()) {
      getInfoOnTokensOfType(cinfo, "library tokens", "lib:", "libversion", "unknown");
      getInfoOnTokensOfType(cinfo, "image tokens", "image:", "libversion", "unknown");
      JsonObject udfList = new JsonObject();
      UserDefinedMacroFunctions UDF = UserDefinedMacroFunctions.getInstance();
      for (String name : UDF.getAliases()) {
        udfList.addProperty(name, UDF.getFunctionLocation(name));
      }
      cinfo.add("user defined functions", udfList);
      cinfo.addProperty("client id", MapTool.getClientId());
    }
    return cinfo;
  }

  /**
   * Gets info on tokens with names starting with the prefix.
   *
   * @param cinfo json object to add info to
   * @param token_type token type
   * @param prefix token prefix (e.g. "lib:" "image:")
   * @param versionProperty Property (if any) to get token version from
   * @param unknownVersionText text to show if version is unknown
   */
  private void getInfoOnTokensOfType(
      JsonObject cinfo,
      String token_type,
      String prefix,
      String versionProperty,
      String unknownVersionText) {
    JsonObject libInfo = new JsonObject();
    Streams.stream(MapTool.getFrame().getZoneRenderers().listIterator())
            .parallel()
            .flatMap(zr -> zr.getZone().getAllTokens().stream())
            .filter(t -> t.getName().toLowerCase(Locale.ROOT).startsWith(prefix))
            .forEach(t -> libInfo.addProperty(t.getName(),
                t.getProperty(versionProperty) != null
                ? t.getProperty(versionProperty).toString()
                : unknownVersionText));
    if (!libInfo.isEmpty()) {
      cinfo.add(token_type, libInfo);
    }
  }

  private String getTimeDate() {
    Calendar cal = Calendar.getInstance();
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    return sdf.format(cal.getTime());
  }

  private String getIsoTimeDate() {
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now());
  }

  /**
   * Retrieves the server side preferences as a json object.
   *
   * @return the server side preferences
   */
  private JsonObject getServerInfo() {
    ServerPolicy sp = MapTool.getServerPolicy();

    return sp.toJSON();
  }

  /**
   * Retrieves information about the campaign as a json object.
   *
   * @return the campaign information.
   * @throws ParserException if an error occurs.
   */
  private JsonObject getCampaignInfo() throws ParserException {

    Gson gson = new Gson();

    if (!MapTool.getParser().isMacroTrusted()) {
      throw new ParserException(
          I18N.getText("macro.function.general.noPerm", "getInfo('campaign')"));
    }
    JsonObject cinfo = new JsonObject();
    Campaign c = MapTool.getCampaign();
    CampaignProperties cp = c.getCampaignProperties();

    cinfo.addProperty("id", c.getId().toString());
    cinfo.addProperty(
        "initiative movement locked",
        FunctionUtil.getDecimalForBoolean(cp.isInitiativeMovementLock()));
    cinfo.addProperty(
        "initiative owner permissions",
        FunctionUtil.getDecimalForBoolean(cp.isInitiativeOwnerPermissions()));

    JsonArray zoneIds = new JsonArray();
    JsonObject zinfo = new JsonObject();
    for (Zone z : c.getZones()) {
      zoneIds.add(z.getId().toString());
      zinfo.addProperty(z.getName(), z.getId().toString());
    }
    cinfo.add("zoneIDs", zoneIds);
    cinfo.add("zones", zinfo);

    JsonArray tinfo = new JsonArray();
    for (LookupTable table : c.getLookupTableMap().values()) {
      tinfo.add(table.getName());
    }
    cinfo.add("tables", tinfo);

    JsonArray ttinfo = new JsonArray();
    c.getTokenTypes().forEach(ttinfo::add);
    cinfo.add("token types", ttinfo);

    JsonObject llinfo = new JsonObject();
    for (String ltype : c.getLightSourcesMap().keySet()) {
      JsonArray ltinfo = new JsonArray();
      for (LightSource ls : c.getLightSourceMap(ltype).values()) {
        JsonObject linfo = new JsonObject();
        linfo.addProperty("name", ls.getName());
        linfo.addProperty("max range", ls.getMaxRange());
        linfo.addProperty("type", ls.getType().name());
        linfo.addProperty("scale", ls.isScaleWithToken());
        linfo.addProperty("ignores-vbl", ls.isIgnoresVBL());

        JsonArray lightList = new JsonArray();
        for (Light light : ls.getLightList()) {
          lightList.add(gson.toJsonTree(light));
        }
        linfo.add("light segments", lightList);
        ltinfo.add(linfo);
      }
      llinfo.add(ltype, ltinfo);
    }
    cinfo.add("light sources", llinfo);

    JsonObject sinfo = new JsonObject();
    for (BooleanTokenOverlay bto : c.getTokenStatesMap().values()) {
      String group = bto.getGroup();
      if (group == null || group.isEmpty()) {
        group = "no group";
      }
      JsonArray sgroup;
      if (sinfo.has(group)) {
        sgroup = sinfo.get(group).getAsJsonArray();
      } else {
        sgroup = new JsonArray();
      }
      JsonObject state = new JsonObject();
      state.addProperty("name", bto.getName());
      state.addProperty("type", bto.getClass().getSimpleName());
      state.addProperty("group", group);
      state.addProperty("isShowGM", bto.isShowGM() ? BigDecimal.ONE : BigDecimal.ZERO);
      state.addProperty("isShowOwner", bto.isShowOwner() ? BigDecimal.ONE : BigDecimal.ZERO);
      state.addProperty("isShowOthers", bto.isShowOthers() ? BigDecimal.ONE : BigDecimal.ZERO);
      state.addProperty(
          "isImageOverlay", (bto instanceof ImageTokenOverlay) ? BigDecimal.ONE : BigDecimal.ZERO);
      // TODO Shouldn't assetId be included if isImageOverlay is true??
      state.addProperty("mouseOver", bto.isMouseover() ? BigDecimal.ONE : BigDecimal.ZERO);
      state.addProperty("opacity", bto.getOpacity());
      state.addProperty("order", bto.getOrder());
      if (bto instanceof FlowColorDotTokenOverlay) {
        state.addProperty("gridSize", ((FlowColorDotTokenOverlay) bto).getGrid());
      }
      if (bto instanceof CornerImageTokenOverlay) {
        state.addProperty("corner", ((CornerImageTokenOverlay) bto).getCorner().name());
      }

      sgroup.add(state);
      sinfo.add(group, sgroup);
    }
    cinfo.add("states", sinfo);

    JsonArray remoteRepos = new JsonArray();
    for (String repo : c.getRemoteRepositoryList()) {
      remoteRepos.add(repo);
    }
    cinfo.add("remote repository", remoteRepos);

    JsonObject sightInfo = new JsonObject();
    for (SightType sightType : c.getSightTypeMap().values()) {
      JsonObject si = new JsonObject();
      if (sightType.getShape() == ShapeType.BEAM) {
        si.addProperty("width", sightType.getWidth());
        si.addProperty("offset", sightType.getOffset());
      }
      if (sightType.getShape() == ShapeType.CONE) {
        si.addProperty("arc", sightType.getArc());
        si.addProperty("offset", sightType.getOffset());
      }
      si.addProperty("distance", sightType.getDistance());
      si.addProperty("multiplier", sightType.getMultiplier());
      si.addProperty("shape", sightType.getShape().name());
      si.addProperty("scale", sightType.isScaleWithToken());

      JsonArray lightList = null;
      if (sightType.getPersonalLightSource() != null) {
        lightList = new JsonArray();
        for (Light light : sightType.getPersonalLightSource().getLightList()) {
          lightList.add(gson.toJsonTree(light));
        }
      }
      si.add("personal lights", lightList);

      sightInfo.add(sightType.getName(), si);
    }
    cinfo.add("sight", sightInfo);

    JsonObject barinfo = new JsonObject();
    for (BarTokenOverlay tbo : c.getTokenBarsMap().values()) {
      String group = tbo.getGroup();
      if (group == null) {
        group = "no group";
      }
      JsonArray bgroup;
      if (barinfo.has(group)) {
        bgroup = barinfo.get(group).getAsJsonArray();
      } else {
        bgroup = new JsonArray();
      }
      JsonObject bar = new JsonObject();
      bar.addProperty("name", tbo.getName());
      bar.addProperty("type", tbo.getClass().getSimpleName());
      bar.addProperty("side", tbo.getSide().toString());
      bar.addProperty("increment", tbo.getIncrements());
      bar.addProperty("mouseOver", tbo.isMouseover() ? BigDecimal.ONE : BigDecimal.ZERO);
      bar.addProperty("isShowGM", tbo.isShowGM() ? BigDecimal.ONE : BigDecimal.ZERO);
      bar.addProperty("isShowOwner", tbo.isShowOwner() ? BigDecimal.ONE : BigDecimal.ZERO);
      bar.addProperty("isShowOthers", tbo.isShowOthers() ? BigDecimal.ONE : BigDecimal.ZERO);
      // TODO TokenBars can have images; should we include assetIds??
      bgroup.add(bar);
      barinfo.add(group, bgroup);
    }
    cinfo.add("bars", barinfo);

    return cinfo;
  }

  /**
   * Get Theme Info
   *
   * @return JsonObject of theme information
   */
  private JsonObject getThemeInfo() {
    JsonObject theme = new JsonObject();

    // Currently, just the color info is returned.
    for (Map.Entry<Object, Object> entry : UIManager.getDefaults().entrySet()) {
      if (entry.getValue() instanceof Color color) {
        // TODO Other functions use String.format("#%h", ...) instead -- same here??
        theme.addProperty((String) entry.getKey(), Integer.toHexString(color.getRGB()));
      }
    }
    return theme;
  }

  /**
   * Get Theme List
   *
   * @return JsonObject of all loaded theme names and their
   * info (as per <code>getInfo("theme")</code>).
   */
  private JsonObject getThemeList() {
    JsonObject theme = new JsonObject();

    // Currently, just the color info is returned.
    for (Map.Entry<Object, Object> entry : UIManager.getDefaults().entrySet()) {
      if (entry.getValue() instanceof Color color) {
        // TODO Other functions use String.format("#%h", ...) instead -- same here??
        theme.addProperty((String) entry.getKey(), Integer.toHexString(color.getRGB()));
      }
    }
    return theme;
  }

  /**
   * Retrieves debug information
   *
   * @return the debug information.
   */
  private JsonObject getDebugInfo() {
    return sysInfoProvider.getSysInfoJSON();
  }
}
