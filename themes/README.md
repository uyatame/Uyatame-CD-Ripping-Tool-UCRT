# UCRT のテーマ

UCRT の見た目の仕上げ(色・角の丸み・ボタンや情報の表示のしかた)は、テーマのファイルで変えられます。
配置と操作は、どのテーマでも同じです。

## 使い方

1. テーマのファイル(`.ucrttheme.json` など)をダウンロードします。
2. UCRT の **設定 > 表示 > テーマ > ファイルから読み込む** で選びます。
3. 読み込んだテーマは一覧に出て、すぐに切り替わります。消すときは、一覧のごみ箱のボタンを押します。

## 自分のテーマを作る

1. **設定 > 表示 > テーマ** で、もとにしたいテーマを選びます。
2. **ひな形として書き出す** で保存します(組み込みのテーマは、`id` と名前を変えた形で書き出されます)。
3. ファイルの値を書き換えて、**ファイルから読み込む** で読み込みます。

同じ `id` のテーマを読み込むと、前のものを上書きします。少しずつ直しながら試せます。

## 書式

```json
{
  "format": "ucrt-theme",
  "formatVersion": 1,
  "id": "my-theme",
  "name": "My theme",
  "author": "your name",
  "description": "short description",
  "accent": "#F3D58C",
  "seed": "",
  "corner": 16,
  "player": {
    "tabs": "pill",
    "artCorner": 18,
    "artFrame": false,
    "artGlow": false,
    "info": "filled",
    "controls": "solid",
    "seek": "thin",
    "titleWeight": 900,
    "blurLevel": 0
  }
}
```

| 項目 | 値 | 意味 |
|---|---|---|
| `format` | `ucrt-theme` | 必ずこの値 |
| `id` | 英小文字・数字・`-`・`_`(40 文字まで) | テーマの識別子。同じ `id` は上書き |
| `name` | 40 文字まで | 一覧に出る名前 |
| `author` / `description` | 文字 | 一覧に出る作者と説明(なくてもよい) |
| `accent` | `#RRGGBB` か `#AARRGGBB`、空 | 再生画面の差し色(アンビエントモードのとき)。空なら標準 |
| `seed` | `#RRGGBB`、空 | アプリ全体のテーマの色。空なら設定の色・ダイナミックカラーのまま |
| `corner` | -1〜32 | アプリ全体の角の丸み(dp)。-1 は標準 |
| `player.tabs` | `underline` / `pill` / `segmented` | 再生画面の上の「お気に入り・再生中・再生リスト」 |
| `player.artCorner` | 0〜40 | ジャケットの角の丸み(dp) |
| `player.artFrame` | `true` / `false` | ジャケットを細い額縁で囲む |
| `player.artGlow` | `true` / `false` | ジャケットのまわりを差し色で光らせる |
| `player.info` | `outline` / `filled` / `dark` / `panel` | 形式・出力先・EQ の表示。`panel` は 1 枚の表示窓にまとめる |
| `player.controls` | `outline` / `solid` / `capsule` / `square` | 前・再生・次のボタン |
| `player.seek` | `default` / `thin` / `ticks` | 再生バー |
| `player.titleWeight` | 400〜900 | 曲名の太さ |
| `player.blurLevel` | 0〜10 | 背景のぼかしの強さ。0 は設定のまま |

知らない値や範囲の外の値は、標準の値に置き換えて読み込みます。`_help` など、ほかの項目は読み飛ばします。

## 組み込みのテーマ

| id | 名前 | 中身 |
|---|---|---|
| `classic` | 標準 | 今までの見た目 |
| `sumi` | 澄 | 塗りの再生ボタン、やわらかいチップ、細いつまみ |
| `stage` | 舞台 | ジャケットのまわりの光、1 本の帯にまとめたボタン |
| `instrument` | 計器 | 1 枚の表示窓、ジャケットの額縁、目盛り付きの再生バー、角丸の四角のボタン |

[`example.ucrttheme.json`](example.ucrttheme.json) は、そのまま読み込めるサンプルです。
