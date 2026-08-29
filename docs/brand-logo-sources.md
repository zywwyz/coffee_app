# Bundled brand-logo sources

获取日：2026-08-17。以下资源仅用于个人本地识别；品牌名称、商标及图形权利仍归各主体所有。

## 2026-08-28 日历展示版审计补充

用户明确确认 `assets/brand-logos/reference/calendar-logo-reference.png` 中的日历展示版本。该文件为用户提供的旧预览，SHA-256 为 `795e9fd6ca4db0dc8882b2647ec2fd80f0d73ee49912bc9965d8259b0bdc3462`，作为受版本控制的审计输入。`scripts/normalize_brand_logos.py` 对其中的固定像素区域作可复现裁切，并仅移除与裁切边缘连通的浅色日历背景；每个派生裁切在去背景后均保留至少 2px 的透明边，且不包含日期或计数徽标。不重绘、变形或烘焙白色卡片底。

| 输出资源 | 展示输入 | 参考裁切坐标（left, top, right, bottom） |
| --- | --- | --- |
| `brand_logo_arabica.png` | 用户确认旧预览派生：紧凑深色 `% ARABICA` 方块标识 | `(24, 1387, 125, 1484)` |
| `brand_logo_luckin.png` | 用户确认旧预览派生：蓝鹿 + `luckin coffee` 竖向完整标识 | `(140, 1378, 237, 1480)` |
| `brand_logo_cotti.png` | 用户确认旧预览派生：黑色两行 `Cotti Coffee` | `(485, 1035, 580, 1098)` |
| `brand_logo_kcoffee.png` | 用户确认旧预览派生：肯悦咖啡 `KCOFFEE` 横向字标 | `(711, 1400, 810, 1470)` |
| `brand_logo_manner.png` | 用户确认旧预览派生：紧凑深色 `MANNER` 方块标识 | `(26, 1556, 124, 1640)` |
| `brand_logo_hucoffee.png` | 用户确认旧预览派生：沪咖 `JENNY X COFFEE` 招牌 | `(370, 1205, 468, 1300)` |
| `brand_logo_nowwa.png` | 用户确认旧预览派生：橙色图形 + `NOWWA` 竖向完整标识 | `(598, 1020, 694, 1120)` |
| `brand_logo_peets.png` | 用户确认旧预览派生：紧凑竖向 `Peet's Coffee` 标识 | `(712, 1212, 810, 1305)` |

仅 LuckyCup、M Stand、Starbucks、Tims 继续使用 `assets/brand-logos/source/` 的原始高分审计资源。所有展示资源统一为透明 512×512 画布：普通标识最大边 430px，横向字标最大边 450px；日历容器另保留单层 3dp 安全间距。

当前 12 个输出的文件 SHA-256 与解码后 ARGB 像素 SHA-256 如下。像素指纹由 `BundledBrandLogoTest` 按稳定 brand id 断言：PNG 的无损编码或元数据变化不影响该断言，任一像素变化均需人工复审。

| brand id | 当前输入 | 输出文件 SHA-256 | 解码像素 SHA-256 |
| --- | --- | --- | --- |
| `seed-chain-luckin` | reference `(140, 1378, 237, 1480)` | `b7c86de8e3a63cb20e7294f4b14b6c85feb22e164e32b53c07e9ee19696ee9b1` | `2ba8c1c1359786d4980303489ee4b329554521533ff75c3e2e0731e62a3b03e4` |
| `seed-chain-cotti` | reference `(485, 1035, 580, 1098)` | `74ef451e9ad4694518f1a822bee30a66d7da64342ad011b42a75109f092bfdb4` | `799063832440069842204c8aed10f7fee620f22e1b70d115df72f09303642f29` |
| `seed-chain-nowwa` | reference `(598, 1020, 694, 1120)` | `9f3ed8cdb537f0a5c0e24e8107f9b2151901f21d6b6b7a4ac5a96dedf4456a85` | `b43aa74583ae4056d34b3b9d4d4fdc73bb337dea9dae62827a21af9f70c2f68b` |
| `seed-chain-lucky-cup` | source | `9279e2da6267478f5e0750ebe30043269a9692200aceda5e2a6f949ad77c95a1` | `ad21987c3104fa81395ffffe077ca1013f652b49197f47d2d4b25370cb5be5e0` |
| `seed-chain-starbucks` | source | `f1a864970f8cf1b29678adbc3fc15ad2d75c985f115597a105ce7c6fe094e8f3` | `b390417c47778e611c1562dde83bd20ef6ec2a84d999c0c093e27f44bc02e304` |
| `seed-chain-kcoffee` | reference `(711, 1400, 810, 1470)` | `a2954112041316d76499562560dd10b32bfc984d01af4df916877f0e4c37f72a` | `e2b82809a099e6ffcfe6f1e644d9b0711a06dbd8e269940484002d85262a860b` |
| `seed-chain-manner` | reference `(26, 1556, 124, 1640)` | `6be48435f67fc32c711adbccede92d5fb36751c535f7a1db7622104b9c458ddb` | `07e2f7c918683b99fb0f5b2cfef1fa5e2a77e316d915c9d694aac242dab494eb` |
| `seed-chain-hucoffee` | reference `(370, 1205, 468, 1300)` | `d5d32cdde1366ea3f414bdc02bb9e076696c73870db1e5721a513d049d779c26` | `b31f4fc6a8f9360b4c92f0ddd8d4afbb59116fe263dedc8d5e8b6b24c2a33592` |
| `seed-chain-tims` | source | `c87b36ae298a45c74cdf59895cb6b8cb61316682ec1eb64d7bb1377e5f4687e3` | `657f671dcb778ece6cdbfbeb2c2a75f03bb2adefd6073975ff7ee6c0c5da9334` |
| `seed-chain-mstand` | source | `3de36ada75841f32ab3b1820cac03b4ed9809812d4514af7999ca37ae0d49979` | `52be31423e7be8b5f5bef22c8e08b531910468e136f5c1fb0a12a8c81fa5c0a4` |
| `seed-chain-peets` | reference `(712, 1212, 810, 1305)` | `1e152958403bfbdd302c73628dec5810eeed3a09db1a60e50303365e32ea7406` | `b3cb9f934438991497a94ba8ced6324cc5d2ce95a33880ed2bd44b0b01b773e4` |
| `seed-chain-arabica` | reference `(24, 1387, 125, 1484)` | `27202465c44b49214534017dd31b57c1225cd86b10dde7cacdba0ccc168be786` | `9d0bc11393b269e7c69ea131cea3b0774a4396554e8124a3e9e973bb6208a2d0` |

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

所有 Android 输出资源位于 `app/src/main/res/drawable-nodpi/`；2026-08-28 使用 `python3 scripts/normalize_brand_logos.py`（`scripts/requirements-brand-logos.txt` 固定 `Pillow==11.3.0`，本批已据此验证）对审计输入机械去除透明外边、Lanczos 等比缩放并透明补边为 512×512。用户确认的八个展示版本使用本页所列的受控参考预览裁切；其余四个资源继续使用原高分审计源。横向字标使用 450px artwork box，其他品牌使用 430px artwork box；四周最小透明安全边为 31px。该离线资产维护依赖不进入 Android runtime 或构建产物。未引入第三方图、AI 生成、重绘或来源替换。
