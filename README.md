# Video İndirici

IDM tarzı video indirici. Tarayıcı eklentisi sayfada oynayan videoyu ve altyazısını yakalar, bilgisayarında çalışan `app.py` (yt-dlp + ffmpeg) indirir. Altyazılar videonun içine gömülür.

```
video-indirici/
├─ app.py             İndirme motoru + web arayüzü (http://127.0.0.1:5000)
├─ kurulum.bat        Windows: gerekenlerin hepsini tek tıkla kurar
├─ baslat.bat         Windows: programı açar
├─ teshis.py          Bağlantı sorunlarını bulan teşhis betiği
├─ requirements.txt   Python bağımlılıkları
├─ extension/         Chrome / Edge / Firefox eklentisi
│  ├─ background.js   Ağ trafiğini izler, videoları ve altyazıları yakalar
│  ├─ detect.js       Video / altyazı / m3u8 tanıma (saf fonksiyonlar)
│  ├─ sniff.js        Uzantısı gizlenmiş akışları içerikten tanır (IDM tarzı)
│  ├─ relay.js        sniff.js'in bulduklarını eklentiye iletir
│  ├─ content.js      Videonun üstündeki "⬇ İndir" butonu
│  └─ popup.*         Eklenti penceresi
└─ tests/             Otomatik testler
```

## İndirilmesi gerekenler

| Ne | Zorunlu mu | İndirme bağlantısı |
|---|---|---|
| **Bu proje** | Evet | GitHub'da **Code → Download ZIP** ya da `git clone https://github.com/KorayhanAvcu/video-indirici.git` |
| **Python 3.10+** | Evet | https://www.python.org/downloads/ |
| **ffmpeg** (ffprobe dahil) | Önerilir | https://www.gyan.dev/ffmpeg/builds/ (*ffmpeg-release-essentials.zip*) |
| **Google Chrome / Microsoft Edge** ya da **Firefox 128+** | Evet (eklenti için) | https://www.google.com/chrome/ · https://www.mozilla.org/firefox/ |
| Python paketleri: **yt-dlp, curl_cffi, flask** | Evet | Elle indirilmez, `requirements.txt` ile kurulur (aşağıda) |
| Git | Hayır (ZIP yeterli) | https://git-scm.com/download/win |
| Node.js 18+ | Hayır (sadece eklenti testleri) | https://nodejs.org/ |

## Hızlı kurulum (Windows)

1. Projeyi indir ve ZIP'i bir klasöre çıkar.
2. Klasördeki **`kurulum.bat`** dosyasına çift tıkla. Eksik olan Python, Python paketleri ve ffmpeg'i `winget` ile kendisi kurar.
   Python'u yeni kurduysa pencereyi kapatıp `kurulum.bat`'ı bir kez daha çalıştırman ister.
3. **`baslat.bat`** ile programı aç.
4. Eklentiyi kur (aşağıda 4. adım).

`winget` yoksa (eski Windows 10) yukarıdaki tablodaki bağlantılardan elle indir, sonra aşağıdaki adımları izle.

## Elle kurulum

### 1. Gerekli programlar

| Program | Neden | Kurulum (Windows) | Kontrol |
|---|---|---|---|
| **Python 3.10+** | `app.py` çalıştırmak için | [python.org](https://www.python.org/downloads/) — kurarken **"Add python.exe to PATH"** kutusunu işaretle | `python --version` |
| **ffmpeg** (ffprobe dahil) | Parçaları birleştirmek, MP4 yapmak, altyazı gömmek | `winget install Gyan.FFmpeg` | `ffmpeg -version` |
| **Chrome / Edge** ya da **Firefox 128+** | Eklenti için | — | — |
| Node.js 18+ *(isteğe bağlı)* | Sadece eklenti testlerini çalıştırmak için | `winget install OpenJS.NodeJS.LTS` | `node --version` |

> `winget install` sonrası terminali kapatıp yeniden aç; yoksa `ffmpeg` komutu bulunamaz.
> ffmpeg yoksa program yine çalışır ama bazı videolar tek parça inemez ve altyazı gömülmez.

### 2. Python paketleri

Proje klasöründe:

```cmd
python -m pip install -r requirements.txt
```

Bu komut şunları kurar:

- **yt-dlp** — indirme motoru
- **curl_cffi** — Cloudflare korumalı sitelerde isteği gerçek tarayıcı gibi imzalar
- **flask** — yerel web arayüzü

`pip` yerine `python -m pip` yazmak önemli: paket, `app.py`'yi çalıştıran Python'a kurulur.
PowerShell köşeli paranteze takılırsa tek tek kur: `python -m pip install -U "yt-dlp[default,curl-cffi]" flask`

yt-dlp siteler değiştikçe güncellenir; ara ara güncelle:

```cmd
python -m pip install -U -r requirements.txt
```

### 3. app.py'yi çalıştır

```cmd
python app.py
```

Tarayıcıda arayüz açılır. Açılışta şu satırı gör:

```
Cloudflare korumalı siteler (impersonation): acik
```

`KAPALI` yazıyorsa 2. adımı tekrarla. İndirirken bu pencere açık kalmalı. Dosyalar `İndirilenler\Video Indirici` klasörüne kaydedilir (`VIDEO_DIR` ortam değişkeniyle değiştirilebilir).

### 4. Eklentiyi kur

**Chrome / Edge**
1. Adres çubuğuna `chrome://extensions` (Edge'de `edge://extensions`) yaz.
2. Sağ üstten **Geliştirici modu**'nu aç.
3. **Paketlenmemiş öğe yükle** → `extension` klasörünü seç.
4. Yapboz simgesinden **Video İndirici**'yi araç çubuğuna sabitle.

Kodu güncelledikten sonra `chrome://extensions` sayfasında Video İndirici'nin **↻ (yenile)** düğmesine bas ve açık sekmeleri yenile.

**Firefox (128+)**
1. Adres çubuğuna `about:debugging#/runtime/this-firefox` yaz.
2. **Geçici Eklenti Yükle** → `extension/manifest.json` dosyasını seç.
3. Video yakalanmıyorsa `about:addons` → Video İndirici → **İzinler** sekmesinden tüm sitelere izni aç.

Not: Firefox'ta geçici eklenti tarayıcı kapanınca kaldırılır. Kalıcı olması için imzalanması gerekir.

## Kullanım

- **Video üstündeki "⬇ İndir" butonu:** Fareyi videonun üzerine getir, sağ üstte çıkan butona bas. Eklenti en iyi kaynağı (çok kaliteli m3u8 > dash > en büyük dosya) seçip gönderir.
- **Eklenti simgesi:** Sayfada yakalanan tüm videoları ve bulunan altyazıları listeler. Kalite ve "Sadece ses (MP3)" buradan seçilir.
- **"Sayfayı yt-dlp ile indir":** YouTube, ok.ru gibi yt-dlp'nin tanıdığı sitelerde en iyi sonucu bu verir.
- **Arayüz (http://127.0.0.1:5000):** Link yapıştırarak indirme, ilerleme, iptal, tekrar dene. "Aynı anda" ile 1–30 arası paralel indirme.

Video listede görünmüyorsa videoyu birkaç saniye oynat. Eklenti videoyu ancak tarayıcı onu indirmeye başlayınca görür.

**Cloudflare "Gerçek kişi olduğunuzu doğrulayın" çıkan sitelerde** sayfa linkini arayüze yapıştırmak işe yaramaz, çünkü yt-dlp o doğrulamayı geçemez. Doğrulamayı tarayıcıda kendin geç, videoyu oynat, sonra "⬇ İndir" butonunu kullan. IDM'in yaptığı da bu: tarayıcının zaten açtığı akışı yakalar.

### Altyazılar

- Eklenti sayfada yüklenen altyazıları (`.vtt`, `.srt`, `.ass`, uzantısı gizli olanlar dahil) yakalar; eklenti penceresinde "Altyazı bulundu" satırında görünür.
- Altyazılar videonun **içine gömülür** (oynatıcıda açıp kapatılabilir, dil etiketli) ve yanına `Video Adı.tr.vtt` olarak da kaydedilir.
- Akışın kendi altyazı kanalları (HLS altyazı kanalı, YouTube vb.) Türkçe ve İngilizce için indirilir.
- Windows-1254 kodlu Türkçe SRT'ler UTF-8'e çevrilir; ş, ğ, ı bozulmaz.
- Eklenti, tarayıcının zaten indirdiği altyazının **içeriğini** gönderir; app.py linki tekrar istemez. Bu yüzden süreli, tek kullanımlık ya da korumalı altyazı linkleri de sorun olmaz.
- İçerik alınamazsa app.py altyazıyı tarayıcının başlıklarıyla ve **videodan önce** indirir (sırada beklerken link ölmesin).
- Altyazı inmezse video yine iner; arayüzde hangi altyazının neden inmediği yazar (ör. `TR: HTTP 403`).
- Görüntüye basılı (hardsub) altyazılar zaten videonun parçasıdır.

## Sorun giderme

| Belirti | Sebep / Çözüm |
|---|---|
| `HTTP Error 403 caused by Cloudflare anti-bot challenge` | `curl_cffi` kurulu değil. Kurulum 2. adımı uygula, açılış satırında `acik` görmelisin. |
| `curl: (28) Connection timed out` | Sunucuya bağlanılamıyor. `python teshis.py` çalıştır: DNS engeli, IPv6 ya da vekil (proxy) sorununu gösterir. |
| `Sunucu video yerine şunu döndü: "security error"` | Link süreli ya da tek kullanımlık. Sayfayı yenile, videoyu oynat, eklentiden hemen tekrar gönder. |
| `Altyazı bulundu ama indirilemedi — TR: HTTP 403` | Altyazı sunucusu isteği reddetti. Sayfayı yenile, videoyu altyazı açıkken birkaç saniye oynat, eklentiden tekrar gönder (eklenti içeriği oynatma sırasında yakalar). `zaman aşımı` yazıyorsa `python teshis.py`. `altyazı değil: ...` yazıyorsa sunucu altyazı yerine hata sayfası döndürmüştür. |
| Video yakalanmıyor | Videoyu birkaç saniye oynat; olmazsa sayfayı yenile. Eklentiyi güncellediysen ↻ düğmesine bas. |
| `app.py çalışmıyor` (eklentide) | Terminalde `python app.py` açık olmalı. |

`app.py` her indirmede terminale hangi adresi, hangi başlık ve kaç çerezle istediğini yazar; hata bildirirken bu satırı da ekle.

## Nasıl çalışıyor

1. `background.js` sekmelerin ağ trafiğini izler. `detect.js` adrese ve Content-Type'a bakarak videoları (`mp4`, `webm`, `m3u8`, `mpd`) ve altyazıları ayırır; `.ts`/`.m4s` parçaları ve 1 MB altı dosyalar elenir.
2. `sniff.js` sayfanın kendi fetch/XHR yanıtlarının ilk baytlarına bakar: `#EXTM3U` ile başlayan her şey akış listesi, `WEBVTT`/SRT/ASS altyazıdır. Böylece `.txt`, `.jpg` ya da uzantısız adreslerle gizlenen listeler de yakalanır.
3. İndir'e basınca adres, sayfa başlığı, **tarayıcının o isteğe eklediği başlıkların tamamı** (Referer, X-Requested-With, Sec-Fetch-* …) ve sitenin çerezleri `app.py`'ye gider. Çerezler her iş için geçici bir `cookies.txt` dosyasına yazılır ve iş bitince silinir.
4. yt-dlp önce normal bağlantıyla dener; Cloudflare engeli çıkarsa tarayıcı taklidiyle (curl_cffi) tekrar dener. Parçaları 8'erli indirir, ffmpeg ile birleştirip MP4 yapar, altyazıları gömer.

Güvenlik: `app.py` yalnızca `127.0.0.1`'de dinler ve değişiklik yapan istekleri ancak `X-Video-Indirici: 1` başlığı varsa kabul eder. Böylece başka bir web sitesi senin bilgisayarında gizlice indirme başlatamaz.

## Sınırlar

- DRM'li (Widevine) yayınlarda parçalar şifreli olduğu için oynatılabilir dosya çıkmaz.
- Eklenti, video oynatılmadan önce açılmış sekmelerde videoyu göremeyebilir; sayfayı yenile.
- Bu araç kişisel kullanım içindir; indirdiğin içeriğin telif ve kullanım koşullarına uymak senin sorumluluğundadır.

## Testler

```cmd
python -m pip install pytest
python -m pytest tests            :: app.py (uçtan uca testler ffmpeg ister)
node --test tests/detect.test.js  :: eklentinin video/altyazı tanıması
```
