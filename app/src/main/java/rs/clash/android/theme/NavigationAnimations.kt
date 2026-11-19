package rs.clash.android.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry
import com.ramcosta.composedestinations.spec.DestinationStyle
import rs.clash.android.ui.components.NavigationItem

object ScaleTransitions : DestinationStyle.Animated() {
	override val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
		slideInVertically(
			initialOffsetY = { it / 3 },
			animationSpec =
				spring(
					dampingRatio = Spring.DampingRatioMediumBouncy,
					stiffness = Spring.StiffnessLow,
				),
		) +
			fadeIn(
				animationSpec =
					tween(
						durationMillis = 300,
						easing = FastOutSlowInEasing,
					),
			)
	}

	override val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
		fadeOut(
			animationSpec =
				tween(
					durationMillis = 200,
					easing = FastOutSlowInEasing,
				),
		)
	}

	override val popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
		fadeIn(
			animationSpec =
				tween(
					durationMillis = 200,
					easing = FastOutSlowInEasing,
				),
		)
	}

	override val popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
		slideOutVertically(
			targetOffsetY = { it / 3 },
			animationSpec =
				tween(
					durationMillis = 300,
					easing = FastOutSlowInEasing,
				),
		) +
			fadeOut(
				animationSpec =
					tween(
						durationMillis = 250,
						easing = FastOutSlowInEasing,
					),
			)
	}
}

object SlideHorizontalTransitions : DestinationStyle.Animated() {
	private fun getDestinationIndex(route: String?): Int = NavigationItem.entries.indexOfFirst { it.direction.route == route }

	override val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
		val targetIndex = getDestinationIndex(targetState.destination.route)
		val initialIndex = getDestinationIndex(initialState.destination.route)
		
		val direction =
			if (targetIndex > initialIndex) {
				AnimatedContentTransitionScope.SlideDirection.Left
			} else {
				AnimatedContentTransitionScope.SlideDirection.Right
			}
		
		slideIntoContainer(
			towards = direction,
			animationSpec =
				spring(
					dampingRatio = Spring.DampingRatioNoBouncy,
					stiffness = Spring.StiffnessMedium,
				),
		) +
			fadeIn(
				animationSpec =
					tween(
						durationMillis = 220,
						easing = FastOutSlowInEasing,
					),
			)
	}

	override val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
		val targetIndex = getDestinationIndex(targetState.destination.route)
		val initialIndex = getDestinationIndex(initialState.destination.route)
		
		val direction =
			if (targetIndex > initialIndex) {
				AnimatedContentTransitionScope.SlideDirection.Left
			} else {
				AnimatedContentTransitionScope.SlideDirection.Right
			}
		
		slideOutOfContainer(
			towards = direction,
			animationSpec =
				spring(
					dampingRatio = Spring.DampingRatioNoBouncy,
					stiffness = Spring.StiffnessMedium,
				),
		) +
			fadeOut(
				animationSpec =
					tween(
						durationMillis = 220,
						easing = FastOutSlowInEasing,
					),
			)
	}

	override val popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
		slideIntoContainer(
			towards = AnimatedContentTransitionScope.SlideDirection.Right,
			animationSpec =
				spring(
					dampingRatio = Spring.DampingRatioNoBouncy,
					stiffness = Spring.StiffnessMedium,
				),
		) +
			fadeIn(
				animationSpec =
					tween(
						durationMillis = 220,
						easing = FastOutSlowInEasing,
					),
			)
	}

	override val popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
		slideOutOfContainer(
			towards = AnimatedContentTransitionScope.SlideDirection.Right,
			animationSpec =
				spring(
					dampingRatio = Spring.DampingRatioNoBouncy,
					stiffness = Spring.StiffnessMedium,
				),
		) +
			fadeOut(
				animationSpec =
					tween(
						durationMillis = 220,
						easing = FastOutSlowInEasing,
					),
			)
	}
}
