# UCRT のテーマ

UCRT の見た目の仕上げ(色・角の丸み・ボタンや情報の表示のしかた)は、テーマのファイルで変えられます。
再生画面だけでなく、ライブラリ・ディスク・アップサンプル・ストリーミング・設定など、すべての画面に効きます。
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
  },
  "app": {
    "nav": "pill",
    "card": "filled",
    "sectionTitle": "bold",
    "listRow": "plain",
    "coverCorner": 10,
    "bars": "floating",
    "headlineWeight": 800,
    "background": "plain",
    "header": "large",
    "button": "round",
    "tile": "circle",
    "progress": "thick"
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
| `app.nav` | `bar` / `pill` / `floating` / `line` | 下のタブ。`pill` は選んだタブを横長の丸い面に、`floating` は浮いた丸いバー、`line` は平らな面と上の線 |
| `app.card` | `filled` / `outlined` / `flat` / `panel` | カード(設定の区切り・ディスクの情報・アップサンプルなど)。`flat` は面なしで区切り線、`panel` は枠付きの表示窓 |
| `app.sectionTitle` | `accent` / `caps` / `bold` | カードと一覧の見出し |
| `app.listRow` | `plain` / `tinted` / `divided` | 一覧の行(曲・アルバム・アーティスト・ディスクの曲・設定の項目など) |
| `app.coverCorner` | -1〜40 | 一覧などのジャケットの角の丸み(dp)。-1 は画面ごとの標準 |
| `app.bars` | `bar` / `floating` / `line` | ミニプレーヤー・取り込み中・Spotify の帯 |
| `app.headlineWeight` | 400〜900 | 画面の見出しの太さ |
| `app.background` | `plain` / `tint` | `tint` は画面の上の方をテーマの色で薄く染める |
| `app.header` | `plain` / `large` / `caps` | 画面の見出し。`large` は大きく太い文字と差し色の短い線、`caps` は字間を空けた文字と細い線 |
| `app.button` | `round` / `soft` / `square` | ボタンと検索の枠の形 |
| `app.tile` | `default` / `circle` / `soft` / `square` | アイコンの台の形(ライブラリのカテゴリー・設定の項目・頭文字の丸など) |
| `app.progress` | `default` / `thick` / `blocks` | 進み具合のバー(取り込み・変換・ミニプレーヤー)。`blocks` は区切った四角 |
| `app.glass` | `true` / `false` | `true` でリキッドグラス(下のタブ・ミニプレーヤーなどが、中身の上に浮かぶガラスになる)。設定の「見た目」をリキッドグラスにするのと同じ。なければ `false` |

知らない値や範囲の外の値は、標準の値に置き換えて読み込みます。`_help` など、ほかの項目は読み飛ばします。

## 組み込みのテーマ

| id | 名前 | 中身 |
|---|---|---|
| `classic` | Prism | 今までの見た目 |
| `sumi` | Ensō | 塗りの再生ボタン、やわらかいチップ、細いつまみ。選んだタブが丸い面になる下のタブ、浮いたミニプレーヤー、太い見出し |
| `stage` | Halo | ジャケットのまわりの光、1 本の帯にまとめたボタン。浮いた暗いタブ、行ごとの面、背景の色付け |
| `instrument` | Amber | 1 枚の表示窓、ジャケットの額縁、目盛り付きの再生バー、角丸の四角のボタン。線のタブ、枠付きのカード、区切り線の一覧、角の小さなジャケット |
| `glass` | Glass | リキッドグラス(下のタブ・ミニプレーヤーが浮かぶガラス)に、丸いカードとボタン。ガラスの透明度・色味は設定の「見た目」で変えられる |

[`example.ucrttheme.json`](example.ucrttheme.json) は、そのまま読み込めるサンプルです。
