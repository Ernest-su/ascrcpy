package ernest.ascrcpy.ui.main

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
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
    val placement = settleRemote(dropped, icon, icon, panel, edgeThreshold, collapsed = true)

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
    val placement = settleRemote(Offset(0f, 700f), panel, icon, panel, edgeThreshold, collapsed = false)

    assertEquals(true, placement.collapsed)
    assertEquals(0f, icon.topLeft(placement.center).x)
    assertEquals(700f, icon.topLeft(placement.center).y)
  }

  @Test
  fun panelKeptAwayFromTheEdgesStaysWhereItWasDropped() {
    val dropped = Offset(120f, 700f)
    val placement = settleRemote(dropped, panel, icon, panel, edgeThreshold, collapsed = false)

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
