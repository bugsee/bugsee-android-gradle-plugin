package com.example.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable fun Child(modifier: Modifier = Modifier, enabled: Boolean = true) {}

@Composable fun ProdDefault() {
    Child()
    Child(enabled = false)
}

@Composable fun ProdExplicit() {
    Child(Modifier)
}
