package uk.co.traynor.speedbuddy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.*

@Composable fun DirectionDial(bearing: Double?, changed: (Double)->Unit) {
    val callback by rememberUpdatedState(changed)
    val current by rememberUpdatedState(bearing)
    val colour=MaterialTheme.colorScheme.primary
    val outline=MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        Text(bearing?.let { "Travel direction ${it.toInt()}° · drag the arrow" } ?: "Choose enforced travel direction · north at top",style=MaterialTheme.typography.labelMedium)
        Text("N",style=MaterialTheme.typography.labelSmall)
        Canvas(Modifier.size(140.dp).semantics {
            contentDescription="Camera enforced travel direction"
            stateDescription=bearing?.let { "${it.toInt()} degrees clockwise from north" } ?: "Unknown"
            customActions=listOf(CustomAccessibilityAction("Rotate clockwise 15 degrees") { callback(((current ?: 0.0)+15)%360);true },
                CustomAccessibilityAction("Rotate anticlockwise 15 degrees") { callback(((current ?: 0.0)+345)%360);true })
        }.pointerInput(Unit) {
            fun select(position: Offset) { callback((Math.toDegrees(atan2((position.x-size.width/2).toDouble(),(size.height/2-position.y).toDouble()))+360)%360) }
            detectDragGestures(onDragStart={select(it)}) { change,_->change.consume();select(change.position) }
        }.pointerInput(Unit) {
            detectTapGestures { position->callback((Math.toDegrees(atan2((position.x-size.width/2).toDouble(),(size.height/2-position.y).toDouble()))+360)%360) }
        }) {
            val radius=size.minDimension*.37f
            drawCircle(outline,radius,style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            drawLine(outline,Offset(center.x,center.y-radius-8),Offset(center.x,center.y-radius+8),4.dp.toPx())
            if(bearing!=null) {
                val angle=Math.toRadians(bearing)
                val tip=Offset(center.x+sin(angle).toFloat()*radius,center.y-cos(angle).toFloat()*radius)
                drawLine(colour,center,tip,5.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(colour,6.dp.toPx(),tip)
            }
            drawCircle(colour,5.dp.toPx())
        }
    }
}
