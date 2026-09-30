package ernest.ascrcpy.ui.main

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the placement maths that keeps the floating remote where the user dropped it. A remote
 * that is remembered by a size-specific top-left corner gets re-clamped as soon as the docked icon
 * grows into the panel, which is what used to snap it back to a screen edge.
 */
class FloatingRemotePlacementTest {
  private val width = 1000f
  private val height = 2000f
  private val iconSize = 40f
  private val panelWidth = 204f
  private val panelHeight = 348f
  private val edgeThreshold = 24f

  private val icon = RemoteBounds(width, height, iconSize, iconSize)
  private val panel = RemoteBounds(width, height, panelWidth, panelHeight)

  @Test
  fun topLeftIsDerivedFromCentreAndKeptOnScreen() {
    assertEquals(Offset(480f, 980f), panel.topLeft(Offset(582f, 1154f)))
    assertEquals(Offset(796f, 980f), icon.topLeft(Offset(816f, 1000f)))
  }

  @Test
  fun centreSurvivesARoundTripThroughTopLeft() {
    val centre = Offset(582f, 1154f)
    assertEquals(centre, panel.centerOf(panel.topLeft(centre)))
  }

  @Test
  fun panelIsNeverPushedPastTheRightEdge() {
    assertEquals(panel.maxX, panel.topLeft(Offset(width, height / 2f)).x)
    assertEquals(0f, panel.topLeft(Offset(0f, height / 2f)).x)
  }

  @Test
  fun plainDragKeepsTheDroppedPosition() {
    val dropped = Offset(300f, 900f)
    val placement = keepRemote(dropped, icon, collapsed = true)

    assertEquals(true, placement.collapsed)
    assertEquals(dropped, icon.topLeft(placement.center))
  }

  @Test
  fun droppingTheIconAwayFromAnEdgeExpandsItAroundTheSamePoint() {
    val dropped = Offset(300f, 900f)
    val placement = settleRemote(dropped, dropped.x, icon, icon, panel, edgeThreshold, collapsed = true)

    assertEquals(false, placement.collapsed)
    assertEquals(Offset(300f + iconSize / 2f, 900f + iconSize / 2f), placement.center)
    // Growing the panel must not move it to an edge.
    assertEquals(300f - (panelWidth - iconSize) / 2f, panel.topLeft(placement.center).x)
  }

  @Test
  fun expandingADockedIconKeepsItFullyVisible() {
    val placement = expandRemote(Offset(icon.maxX, 500f), icon, panel)
    val topLeft = panel.topLeft(placement.center)

    assertEquals(panel.maxX, topLeft.x)
    assertEquals(500f - (panelHeight - iconSize) / 2f, topLeft.y)
  }

  @Test
  fun droppingThePanelAgainstAnEdgeDocksItToThatEdge() {
    val left = dockRemote(Offset(10f, 900f), icon, nearLeft = true, nearRight = false)
    assertEquals(true, left.collapsed)
    assertEquals(0f, icon.topLeft(left.center).x)

    val right = dockRemote(Offset(panel.maxX - 10f, 900f), icon, nearLeft = false, nearRight = true)
    assertEquals(true, right.collapsed)
    assertEquals(icon.maxX, icon.topLeft(right.center).x)
  }

  @Test
  fun panelDraggedToTheEdgeCollapsesWithoutJumpingToTheOppositeEdge() {
    val placement = settleRemote(Offset(0f, 700f), 400f, panel, icon, panel, edgeThreshold, collapsed = false)

    assertEquals(true, placement.collapsed)
    assertEquals(0f, icon.topLeft(placement.center).x)
    assertEquals(700f, icon.topLeft(placement.center).y)
  }

  @Test
  fun panelKeptAwayFromTheEdgesStaysWhereItWasDropped() {
    val dropped = Offset(120f, 700f)
    val placement = settleRemote(dropped, dropped.x, panel, icon, panel, edgeThreshold, collapsed = false)

    assertEquals(false, placement.collapsed)
    assertEquals(dropped, panel.topLeft(placement.center))
  }

  @Test
  fun draggingAPanelThatOnlyHappensToSitOnAnEdgeKeepsItOpen() {
    // A wide panel expands from a docked icon into a position that is already flush with the edge.
    // A vertical drag from there must not collapse it.
    val placement = settleRemote(Offset(panel.maxX, 900f), panel.maxX, panel, icon, panel, edgeThreshold,
      collapsed = false)

    assertEquals(false, placement.collapsed)
    assertEquals(panel.maxX, panel.topLeft(placement.center).x)
    assertEquals(900f, panel.topLeft(placement.center).y)
  }

  @Test
  fun nudgingAFlushPanelAwayFromTheEdgeKeepsItOpen() {
    val dropped = Offset(panel.maxX - 30f, 900f)
    val placement = settleRemote(dropped, panel.maxX, panel, icon, panel, edgeThreshold, collapsed = false)

    assertEquals(false, placement.collapsed)
    assertEquals(dropped, panel.topLeft(placement.center))
  }

  @Test
  fun anAreaSmallerThanTheItemDoesNotProduceNegativeBounds() {
    val tiny = RemoteBounds(10f, 10f, panelWidth, panelHeight)

    assertEquals(0f, tiny.maxX)
    assertEquals(0f, tiny.maxY)
    assertEquals(Offset(0f, 0f), tiny.topLeft(Offset(5f, 5f)))
  }
}

/**
 * The remote keeps a single portrait layout and is shrunk to fit short areas. These tests pin the
 * two properties that make that safe: the panel never overflows its own box, and a landscape-sized
 * area really does get a smaller panel with room left to drag it.
 */
class RemoteScalingTest {
  private val density = 3f
  private val fullPanelHeightPx = RemoteLayout.metrics(RemoteLayout.MaxScale).height.value * density

  @Test
  fun aTallAreaKeepsTheFullSizePanel() {
    assertEquals(RemoteLayout.MaxScale, remoteScale(700f * density, fullPanelHeightPx))
    assertEquals(348.dp, RemoteLayout.metrics(RemoteLayout.MaxScale).height)
    assertEquals(204.dp, RemoteLayout.metrics(RemoteLayout.MaxScale).width)
  }

  @Test
  fun aLandscapeAreaShrinksThePanelAndLeavesDragRoom() {
    // Landscape full screen leaves about 280dp of height on a typical phone.
    val landscapeHeightPx = 280f * density
    val scale = remoteScale(landscapeHeightPx, fullPanelHeightPx)

    assertTrue("landscape should shrink the panel, scale=$scale", scale < RemoteLayout.MaxScale)
    val panelHeightPx = RemoteLayout.metrics(scale).height.value * density
    assertTrue("panel ${panelHeightPx}px must fit ${landscapeHeightPx}px", panelHeightPx < landscapeHeightPx)
    assertTrue("no drag room left", landscapeHeightPx - panelHeightPx >= 8f * density)
  }

  @Test
  fun everyAreaHeightKeepsThePanelDragable() {
    // A panel taller than the area would have a zero vertical drag range and sit glued to an edge.
    for (heightDp in 100..1000 step 5) {
      val areaHeightPx = heightDp * density
      val panelHeightPx = RemoteLayout.metrics(remoteScale(areaHeightPx, fullPanelHeightPx)).height.value * density
      assertTrue("panel ${panelHeightPx}px overflows ${areaHeightPx}px", panelHeightPx < areaHeightPx)
    }
  }

  @Test
  fun everyScaleKeepsThePanelBoxFromClippingItsContent() {
    for (step in 0..100) {
      val metrics = RemoteLayout.metrics(step / 100f)
      assertTrue("height clipped at scale ${metrics.scale}", metrics.contentHeight <= metrics.height)
      assertTrue("width clipped at scale ${metrics.scale}", metrics.contentWidth <= metrics.width)
    }
  }

  @Test
  fun shrinkingIsProportionalAcrossEveryToken() {
    val full = RemoteLayout.metrics(1f)
    val half = RemoteLayout.metrics(0.5f)

    assertEquals(full.keySize.value * 0.5f, half.keySize.value, 1f)
    assertEquals(full.keyIconSize.value * 0.5f, half.keyIconSize.value, 1f)
    assertEquals(full.powerSize.value * 0.5f, half.powerSize.value, 1f)
    assertEquals(full.powerIconSize.value * 0.5f, half.powerIconSize.value, 1f)
    assertEquals(full.volumeSize.value * 0.5f, half.volumeSize.value, 1f)
    assertEquals(full.volumeIconSize.value * 0.5f, half.volumeIconSize.value, 1f)
    assertEquals(full.padding.value * 0.5f, half.padding.value, 1f)
    assertEquals(full.keySpacing.value * 0.5f, half.keySpacing.value, 1f)
    assertEquals(full.navSpacing.value * 0.5f, half.navSpacing.value, 1f)
  }

  @Test
  fun anUnknownAreaFallsBackToTheFullSizePanel() {
    assertEquals(RemoteLayout.MaxScale, remoteScale(0f, fullPanelHeightPx))
    assertEquals(RemoteLayout.MaxScale, remoteScale(700f * density, 0f))
  }
}
