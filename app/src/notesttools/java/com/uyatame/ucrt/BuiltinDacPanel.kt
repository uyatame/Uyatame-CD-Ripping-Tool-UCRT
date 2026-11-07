package com.uyatame.ucrt

import androidx.compose.runtime.Composable
import com.uyatame.ucrt.data.AppSettings

/** 内蔵 DAC の調査用の画面は、テスト版(testtools)にだけある。リリース版は何も出さない */
@Composable
fun BuiltinDacPanel(vm: MainViewModel, s: AppSettings) {}
