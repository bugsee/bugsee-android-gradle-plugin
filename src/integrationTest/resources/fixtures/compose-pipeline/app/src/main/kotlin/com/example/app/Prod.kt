package com.example.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable fun Child(modifier: Modifier = Modifier, enabled: Boolean = true) {}

// A default that is NOT the bare Modifier (issue #146): an omitted `modifier` must keep it, so the
// call below must carry no bugseeTag in either plugin order.
val Marker: Modifier = Modifier.then(Modifier)
@Composable fun ChildCustom(modifier: Modifier = Marker) {}

@Composable fun ProdDefault() {
    Child()
    Child(enabled = false)
}

@Composable fun ProdExplicit() {
    Child(Modifier)
}

@Composable fun ProdCustomDefault() {
    ChildCustom()
}
