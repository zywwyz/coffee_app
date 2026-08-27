# Bundled brand-logo sources

获取日：2026-08-17。以下资源仅用于个人本地识别；品牌名称、商标及图形权利仍归各主体所有。

## 2026-08-28 日历展示版审计补充

用户明确确认 `assets/brand-logos/reference/calendar-logo-reference.png` 中的日历展示版本。该文件为用户提供的旧预览，SHA-256 为 `795e9fd6ca4db0dc8882b2647ec2fd80f0d73ee49912bc9965d8259b0bdc3462`，作为受版本控制的审计输入。`scripts/normalize_brand_logos.py` 对其中的固定像素区域作可复现裁切，并仅移除与裁切边缘连通的浅色日历背景；不重绘、变形或烘焙白色卡片底。

| 输出资源 | 展示输入 | 参考裁切坐标（left, top, right, bottom） |
| --- | --- | --- |
| `brand_logo_arabica.png` | 用户确认旧预览派生：紧凑深色 `% ARABICA` 方块标识 | `(26, 1390, 124, 1482)` |
| `brand_logo_luckin.png` | 用户确认旧预览派生：蓝鹿 + `luckin coffee` 竖向完整标识 | `(140, 1390, 237, 1480)` |
| `brand_logo_cotti.png` | 用户确认旧预览派生：黑色两行 `Cotti Coffee` | `(488, 1035, 580, 1098)` |
| `brand_logo_kcoffee.png` | 用户确认旧预览派生：肯悦咖啡 `KCOFFEE` 横向字标 | `(712, 1400, 810, 1470)` |
| `brand_logo_manner.png` | 用户确认旧预览派生：紧凑深色 `MANNER` 方块标识 | `(26, 1570, 124, 1640)` |
| `brand_logo_hucoffee.png` | 用户确认旧预览派生：沪咖 `JENNY X COFFEE` 招牌 | `(370, 1205, 468, 1290)` |
| `brand_logo_nowwa.png` | 用户确认旧预览派生：橙色图形 + `NOWWA` 竖向完整标识 | `(598, 1020, 694, 1120)` |
| `brand_logo_peets.png` | 用户确认旧预览派生：紧凑竖向 `Peet's Coffee` 标识 | `(712, 1220, 810, 1305)` |

仅 LuckyCup、M Stand、Starbucks、Tims 继续使用 `assets/brand-logos/source/` 的原始高分审计资源。所有展示资源统一为透明 512×512 画布：普通标识最大边 430px，横向字标最大边 450px；日历容器另保留单层 3dp 安全间距。

当前 12 个输出的文件 SHA-256 与解码后 ARGB 像素 SHA-256 如下。像素指纹由 `BundledBrandLogoTest` 按稳定 brand id 断言：PNG 的无损编码或元数据变化不影响该断言，任一像素变化均需人工复审。

| brand id | 当前输入 | 输出文件 SHA-256 | 解码像素 SHA-256 |
| --- | --- | --- | --- |
| `seed-chain-luckin` | reference `(140, 1390, 237, 1480)` | `11cb461c6bf984b90c301bbacbc3b69233b4830d0bf1fec58e7997a66b4c779d` | `3e60e171e3d17c8ded7decdaca90e3c7ad770384696ed2a29b8452cdd878af42` |
| `seed-chain-cotti` | reference `(488, 1035, 580, 1098)` | `4c9ab3868a674626b948748f1cd7257bb9419b0005fc2dcaf9adf3eb40e2edb7` | `f8d3986120efdc9c2de8a61e7c1394a129af5041191bc88db04604f9b585946d` |
| `seed-chain-nowwa` | reference `(598, 1020, 694, 1120)` | `5f40af2e89db4b0447b143ad5d7d6c8ca8d637db632ec27cb3360fe01ed94cfb` | `b43aa74583ae4056d34b3b9d4d4fdc73bb337dea9dae62827a21af9f70c2f68b` |
| `seed-chain-lucky-cup` | source | `311a24966814489c55143a997cc771c90024ae1d9cf082eb25ba64a109a59c11` | `ad21987c3104fa81395ffffe077ca1013f652b49197f47d2d4b25370cb5be5e0` |
| `seed-chain-starbucks` | source | `10e222cc6aa2a94c04dfa81459b1133611c45b0173cb8aef6ee938cea5fd548c` | `b390417c47778e611c1562dde83bd20ef6ec2a84d999c0c093e27f44bc02e304` |
| `seed-chain-kcoffee` | reference `(712, 1400, 810, 1470)` | `53bd5484a570761431409abeba06f74d0e262e3148c54231af74ddee32b19f3c` | `e2b82809a099e6ffcfe6f1e644d9b0711a06dbd8e269940484002d85262a860b` |
| `seed-chain-manner` | reference `(26, 1570, 124, 1640)` | `4f5a1b65c1c5fb3c5ad4ed38f4782d2bb407b454eeddf55221c5c863a4b5de8d` | `4c767cafe2f174425577ce0a02178d31c8e8ff1d4e41f972405952bba279edff` |
| `seed-chain-hucoffee` | reference `(370, 1205, 468, 1290)` | `c280376fffb209d91a8cfd7f4125823c81e8adb52359971366c2392efaa57023` | `afd92ff22fd46cb571f182e52cb693e741a4af209b243c3b09adb2812ba37ebb` |
| `seed-chain-tims` | source | `6815ec4c9cc3b77370ad9e8e16eb6869c0ab0c6d015e25a058c9d89e3ee540bb` | `657f671dcb778ece6cdbfbeb2c2a75f03bb2adefd6073975ff7ee6c0c5da9334` |
| `seed-chain-mstand` | source | `56fba840846a0ce9bfad2531a8b27091feab7f43d4edee27d33bb4420b974bf7` | `52be31423e7be8b5f5bef22c8e08b531910468e136f5c1fb0a12a8c81fa5c0a4` |
| `seed-chain-peets` | reference `(712, 1220, 810, 1305)` | `94d0e0ee48074a9d9bbdf5d84e067260bfc9b98f5b09643b4121da940ef91767` | `5da95d466a5aa79326254782ea9bd79cc0503a69a0e17a264507262aa20cf81d` |
| `seed-chain-arabica` | reference `(26, 1390, 124, 1482)` | `7f435dce90b6ded4a7ce7053f1bb26952107b5aa0afad9cc3c4d908be322286d` | `485a3c334bf7a18fecaf95d476526d538efe353a02d705f00e1ef2d0590d37b0` |

| 品牌／主体 | 精确来源页／直接资源 | 2026-08-26 归档输出（尺寸）／SHA-256 | 当时的提取或转换说明 |
| --- | --- | --- | --- |
| 瑞幸／Luckin Coffee | [官网](https://www.luckincoffee.com/cn/menu/signature-lattes)；页面引用资源 `luckin.png` | `brand_logo_luckin.png`（512×512）`d9b2ae47eb2dabb05d25141c1170395524b8850b21c20a2b1d0b8aeef07b71fa` | 原审计资源等比缩至 400px artwork box，透明补边居中。 |
| 库迪／库迪科技 | [官网](https://www.cotticoffee.com/)；页面引用资源 `cotti.png` | `brand_logo_cotti.png`（512×512）`11688606bb2b32c66058c0214361d58dd424cedaf4dc8ce309b60e4747a25dc3` | 保留黑色完整 `Cotti Coffee` 文字版，等比缩至 430px 横向 artwork box，透明补边居中。 |
| NOWWA／挪瓦咖啡 | [官网](https://www.nowwacafe.com/)；官方 SVG `nowwa.svg` | `brand_logo_nowwa.png`（512×512）`17d0e1e6ca1361b7a9b7687f1a4570cdeecce1d0fd41a0bbac37346e5b2cb287` | 原审计资源等比缩至 400px artwork box，透明补边居中。 |
| 幸运咖／蜜雪冰城股份有限公司 | [官网](https://www.xingyunka.com/)；页面资源 `lucky-cup.png` | `brand_logo_lucky_cup.png`（512×512）`562e11fd06583f782c575b61a0cd9bf287d9f2bca5011e90cc0d36217176e0f5` | 原审计资源等比缩至 400px artwork box，透明补边居中。 |
| 星巴克／Starbucks China | [中国官网](https://www.starbucks.com.cn/)；官方 SVG `starbucks.svg` | `brand_logo_starbucks.png`（512×512）`93310a837b050bd16fbbd0a2bea605c847f01c631d2a4f2320ffd86d3f12ad84` | 原审计资源等比缩至 400px artwork box，透明补边居中。 |
| 肯悦咖啡／百胜中国 | [百胜中国 IR 发布](https://ir.yumchina.com/zh-hans/news-releases/news-release-details/kenyuekafeiqingzhuqizaizhongguokaishedi200jiamendianyingwenban)；[官方发布照片](https://mma.prnewswire.com/media/2451669/KCOFFEE_Celebrates_Opening_200th_Store_China.jpg) | `brand_logo_kcoffee.png`（512×512）`84094c9dea84cf97d9f7c7e94486da20b1a4cd130f1e2cdf9195c36559f88e3e` | 完整“肯悦咖啡 KCOFFEE”横向标识保持原样，等比缩至 430px 横向 artwork box，透明补边居中；未重绘或变色。 |
| MANNER／上海茵赫实业 | [官网](https://www.wearemanner.com/)；页面资源 `manner.png` | `brand_logo_manner.png`（512×512）`a2dfe3cd4bbc01b575548e728723c119cf097cbc6c09c0c9ead7a0073edfc570` | 原审计资源等比缩至 400px artwork box，保留深色高对比背景并居中。 |
| 沪咖／沪上阿姨体系 | [港交所正式招股书](https://www.hkexnews.hk/listedco/listconews/sehk/2025/0428/2025042800056_c.pdf)（品牌归属/名称依据，印刷页150／物理页160）；[南方+记者报道](https://static.nfnews.com/content/202408/28/c9692491.html)（像素直接来源，正面品牌车招牌图 `hucoffee-2.png`） | `brand_logo_hucoffee.png`（512×512）`1474938e0cb650c8776c0c15cd63c7e83bc0d9d919bfdfaacd1ddbb64b68adb8` | 完整“沪咖 JENNY X COFFEE”招牌保持原样，等比缩至 400px artwork box，透明补边居中；未重绘或变色。 |
| Tims／TH International | [中国官网](https://www.timschina.com/home/index)；页面资源 `tims.png` | `brand_logo_tims.png`（512×512）`f4b2175ff5fef28684fc2e25257e1d6e5240c54fabd9f88a9b5037ad7a3b3ec3` | 原审计资源等比缩至 400px artwork box，透明补边居中。 |
| M Stand／上海艾恰餐饮服务 | [官网](https://mstand.cn/)；官方 CDN `https://nwzimg.wezhan.cn/contents/sitefiles2047/10235946/images/47286529.png` | `brand_logo_mstand.png`（512×512）`e9f9c5d73562c7664c8155a9c0a55ddeaf82421ab454d9c0a15b8d7857889153` | 原审计资源等比缩至 400px artwork box，保留白色高对比背景并居中。 |
| Peet's／Peet's Coffee | [官网](https://www.peets.com/)；页面资源 `peets.png` | `brand_logo_peets.png`（512×512）`5f151399c39f9634d31664f5974fdb9fb38a947774bd504de68cd43eda1d0f3c` | 原审计资源等比缩至 400px artwork box，透明补边居中。 |
| %Arabica／Arabica International | [官网](https://arabica.com/en/)；[直接 PNG](https://arabica.com/wp-content/uploads/2025/05/arabicalogo-centered.png) | `brand_logo_arabica.png`（512×512）`70a59b32ce4571c8d1ee4805595a52de82bba92ec878e091d82ab002bcea0cbb` | 原审计资源等比缩至 400px artwork box，保留深色高对比背景并居中。 |

以下是历史 `assets/brand-logos/source/` 输入 SHA-256，仅供原始来源追溯；当前生成以本页“日历展示版审计补充”中的 reference/source 映射、裁切坐标和当前输出指纹为准。

| 输入文件 | SHA-256 |
| --- | --- |
| `brand_logo_luckin.png` | `8eb26fad4c4d3db7ee3e61431664ce89424c42e59bfc6cccc871aac0d038ccf8` |
| `brand_logo_cotti.png` | `62dc3a7b2604f6f449ccc45ecb347312257ea60964e1c47e7406a8de7e013a81` |
| `brand_logo_nowwa.png` | `d5909e9ac382154ede600b1442bfdf4563206fe560f93177033f7fafe5971c50` |
| `brand_logo_lucky_cup.png` | `8608c9fff503ac20e0a9d3da4cc41979223a44fc34b13180a37799e8de0d9570` |
| `brand_logo_starbucks.png` | `1cb0b2f817841b1df139e0e79a75cb68dc0d194b4970dc2221d360b3af9870c2` |
| `brand_logo_kcoffee.png` | `b76b68166a47669becd45542d99d12beb35d2a816d7d4b129dd066ec9a11fcc7` |
| `brand_logo_manner.png` | `9dc12e21cd9b81deb12578b2bea4e8b28f993f7029fff6c3120a112c405a6f3e` |
| `brand_logo_hucoffee.png` | `82a9f5b4bd7c0eedcbcc5cfe4479fa325f3ff8a16cb566f8422eea57a4bb2951` |
| `brand_logo_tims.png` | `6c454cb3ff01a338106efae7dbcdd0957113322158895faedbc8d96f702191ad` |
| `brand_logo_mstand.png` | `b3c27012fac90d2a593744b2cf3c4b7e75102fc8744d06c3f98c6484227fb164` |
| `brand_logo_peets.png` | `1071bd412ed57f62aa2c964f0b4ec4b23c123e0d36c6193cf3d36ccd9861bfa5` |
| `brand_logo_arabica.png` | `2f6f79a9b73a2793c54f53922f011a1254c69962e8559ba5296c224e71def20f` |

所有 Android 输出资源位于 `app/src/main/res/drawable-nodpi/`；2026-08-28 使用 `python3 scripts/normalize_brand_logos.py`（`scripts/requirements-brand-logos.txt` 固定 Pillow 版本）对审计输入机械去除透明外边、Lanczos 等比缩放并透明补边为 512×512。用户确认的八个展示版本使用本页所列的受控参考预览裁切；其余四个资源继续使用原高分审计源。横向字标使用 450px artwork box，其他品牌使用 430px artwork box；四周最小透明安全边为 31px。该离线资产维护依赖不进入 Android runtime 或构建产物。未引入第三方图、AI 生成、重绘或来源替换。
