package com.example.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation

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

// Real androidx text fields from other modules (issues #149, #151). `Modifier` is explicit, so the result does
// not depend on plugin order; each call must carry exactly one bugseeSecure in the bytecode.
@Composable fun ProdSecureMaterial() {
    androidx.compose.material.TextField("", {}, Modifier, visualTransformation = PasswordVisualTransformation())
}

@Composable fun ProdSecureMaterialOutlined() {
    androidx.compose.material.OutlinedTextField("", {}, Modifier, visualTransformation = PasswordVisualTransformation())
}

@Composable fun ProdSecureMaterial3() {
    androidx.compose.material3.TextField("", {}, Modifier, visualTransformation = PasswordVisualTransformation())
}

@Composable fun ProdSecureMaterial3Outlined() {
    androidx.compose.material3.OutlinedTextField("", {}, Modifier, visualTransformation = PasswordVisualTransformation())
}

@Composable fun ProdSecureBasic() {
    androidx.compose.foundation.text.BasicTextField("", {}, Modifier, visualTransformation = PasswordVisualTransformation())
}

// Look-alike that must NOT be secured: not androidx, though it sits in a `...Kt` facade like the real ones.
@Composable fun TextField(value: String, modifier: Modifier = Modifier, visualTransformation: androidx.compose.ui.text.input.VisualTransformation? = null) {}
@Composable fun ProdSecureFake() {
    TextField("", Modifier, PasswordVisualTransformation())
}
