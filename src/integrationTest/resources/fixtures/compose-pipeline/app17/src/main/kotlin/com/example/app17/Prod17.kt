package com.example.app17

import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Real foundation 1.7.8 TextFieldState-based text fields (issue #158). They have no VisualTransformation
// parameter, so the compile-time secure pass must leave them alone (zero bugseeSecure calls). Secure at
// runtime: BasicSecureTextField through SemanticsProperties.Password; a state-based BasicTextField opts in
// with Modifier.bugseeSecure().
@Composable fun ProdStateBasic() {
    BasicTextField(TextFieldState(), Modifier)
}

@Composable fun ProdStateBasicMasked() {
    BasicTextField(TextFieldState(), Modifier, outputTransformation = { replace(0, length, "*".repeat(length)) })
}

@Composable fun ProdStateSecure() {
    BasicSecureTextField(TextFieldState(), Modifier)
}

// Positive control on the same foundation: the String overload keeps VisualTransformation and IS secured, so a
// zero above means "not matched", not "the pass was inactive".
@Composable fun ProdValueSecure() {
    BasicTextField("", {}, Modifier, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
}
