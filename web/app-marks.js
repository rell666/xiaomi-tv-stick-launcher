/* The mark library (ours; ES5): every white mark an app's icon can be changed to with Change icon
   (web/app-edit.js). One entry per mark, in the order the picker lists them after "App's own icon" (the first
   marks, then the rest by kind):
     id    the mark's file name: web/assets/icons/<id>.svg, measured in FrameIconMetrics
     name  shown under it in the picker
     svg   its path (always assets/icons/<id>.svg; tests/app-edit.test.js checks it)
     packages  the Android packages that show this mark by default (2026-11-02: an app whose
           package is listed here shows the mark unless the user chose otherwise with Change icon; an
           app no mark lists keeps its own icon). Each one verified, never guessed: its evidence (Google
           Play page, or a stick's installed list) is in docs/mark-packages.json, and a package is
           listed under one mark at most. [] = picker only (iptv: any app; sky, showtime: no Android
           TV app found; smarttube: not on Google Play).
   Adding a mark (README "Adding a mark"): its SVG in web/assets/icons/ (white, 24-unit viewBox, our
   own or with its source and licence in ADDITIONAL-SOURCES.json), its metrics from
   tools/icon_metrics.py in METRICS and its file in OURS (tools/android-patch.py), and one line here
   (with its packages, each recorded in docs/mark-packages.json). */
window.CleanFrameAppMarks = [
  { id: 'iptv', name: 'IPTV', svg: 'assets/icons/iptv.svg', packages: [] },
  { id: 'emby', name: 'Emby', svg: 'assets/icons/emby.svg', packages: ['com.mb.android', 'tv.emby.embyatv'] },
  { id: 'youtube', name: 'YouTube', svg: 'assets/icons/youtube.svg', packages: ['com.google.android.youtube.tv', 'com.rockchip.android.youtube.tv', 'com.google.android.youtube'] },
  { id: 'spotify', name: 'Spotify', svg: 'assets/icons/spotify.svg', packages: ['com.spotify.tv.android', 'com.spotify.music'] },
  { id: 'rumble', name: 'Rumble', svg: 'assets/icons/rumble.svg', packages: ['com.rumble.battles'] },
  { id: 'netflix', name: 'Netflix', svg: 'assets/icons/netflix.svg', packages: ['com.netflix.ninja', 'com.netflix.mediaclient'] },
  { id: 'primevideo', name: 'Prime Video', svg: 'assets/icons/primevideo.svg', packages: ['com.amazon.amazonvideo.livingroom', 'com.amazon.avod.thirdpartyclient'] },
  { id: 'disneyplus', name: 'Disney+', svg: 'assets/icons/disneyplus.svg', packages: ['com.disney.disneyplus'] },
  { id: 'stremio', name: 'Stremio', svg: 'assets/icons/stremio.svg', packages: ['com.stremio.one'] },
  { id: 'appletv', name: 'Apple TV', svg: 'assets/icons/appletv.svg', packages: ['com.apple.atve.androidtv.appletv'] },
  { id: 'hbomax', name: 'HBO Max', svg: 'assets/icons/hbomax.svg', packages: ['com.wbd.stream'] },
  { id: 'tubi', name: 'Tubi', svg: 'assets/icons/tubi.svg', packages: ['com.tubitv'] },
  { id: 'applemusic', name: 'Apple Music', svg: 'assets/icons/applemusic.svg', packages: ['com.apple.android.music'] },
  { id: 'abciview', name: 'ABC iview', svg: 'assets/icons/abciview.svg', packages: ['au.net.abc.iview'] },
  { id: 'sbs', name: 'SBS', svg: 'assets/icons/sbs.svg', packages: ['com.sbs.ondemand.tv', 'com.sbs.ondemand.android'] },
  /* Media servers and players */
  { id: 'jellyfin', name: 'Jellyfin', svg: 'assets/icons/jellyfin.svg', packages: ['org.jellyfin.androidtv', 'org.jellyfin.mobile'] },
  { id: 'plex', name: 'Plex', svg: 'assets/icons/plex.svg', packages: ['com.plexapp.android'] },
  { id: 'kodi', name: 'Kodi', svg: 'assets/icons/kodi.svg', packages: ['org.xbmc.kodi'] },
  { id: 'vlc', name: 'VLC', svg: 'assets/icons/vlc.svg', packages: ['org.videolan.vlc'] },
  { id: 'tivimate', name: 'TiviMate', svg: 'assets/icons/tivimate.svg', packages: ['ar.tvplayer.tv'] },
  { id: 'smarttube', name: 'SmartTube', svg: 'assets/icons/smarttube.svg', packages: [] },
  /* UK TV */
  { id: 'bbciplayer', name: 'BBC iPlayer', svg: 'assets/icons/bbciplayer.svg', packages: ['bbc.iplayer.android'] },
  { id: 'itvx', name: 'ITVX', svg: 'assets/icons/itvx.svg', packages: ['air.ITVMobilePlayer'] },
  { id: 'channel4', name: 'Channel 4', svg: 'assets/icons/channel4.svg', packages: ['com.channel4.ondemand'] },
  { id: 'my5', name: 'My5', svg: 'assets/icons/my5.svg', packages: ['com.channel5.my5'] },
  { id: 'now', name: 'NOW', svg: 'assets/icons/now.svg', packages: ['com.bskyb.nowtv.beta'] },
  { id: 'sky', name: 'Sky', svg: 'assets/icons/sky.svg', packages: [] },
  { id: 'britbox', name: 'BritBox', svg: 'assets/icons/britbox.svg', packages: ['com.britbox.tv', 'com.britbox.us'] },
  /* Streaming */
  { id: 'paramountplus', name: 'Paramount+', svg: 'assets/icons/paramountplus.svg', packages: ['com.cbs.ott', 'com.cbs.app', 'com.cbs.ca'] },
  { id: 'discoveryplus', name: 'discovery+', svg: 'assets/icons/discoveryplus.svg', packages: ['com.discovery.dplay', 'com.discovery.discoveryplus.mobile'] },
  { id: 'plutotv', name: 'Pluto TV', svg: 'assets/icons/plutotv.svg', packages: ['tv.pluto.android'] },
  { id: 'dazn', name: 'DAZN', svg: 'assets/icons/dazn.svg', packages: ['com.dazn'] },
  { id: 'mubi', name: 'MUBI', svg: 'assets/icons/mubi.svg', packages: ['com.mubi'] },
  { id: 'crunchyroll', name: 'Crunchyroll', svg: 'assets/icons/crunchyroll.svg', packages: ['com.crunchyroll.crunchyroid'] },
  { id: 'fubo', name: 'Fubo', svg: 'assets/icons/fubo.svg', packages: ['com.fubo.firetv.screen', 'tv.fubo.mobile'] },
  { id: 'starz', name: 'Starz', svg: 'assets/icons/starz.svg', packages: ['com.bydeluxe.d3.android.program.starz'] },
  { id: 'showtime', name: 'Showtime', svg: 'assets/icons/showtime.svg', packages: [] },
  /* Video */
  { id: 'twitch', name: 'Twitch', svg: 'assets/icons/twitch.svg', packages: ['tv.twitch.android.app'] },
  { id: 'vimeo', name: 'Vimeo', svg: 'assets/icons/vimeo.svg', packages: ['com.vimeo.android.videoapp'] },
  { id: 'dailymotion', name: 'Dailymotion', svg: 'assets/icons/dailymotion.svg', packages: ['com.dailymotion.dailymotion'] },
  { id: 'youtubekids', name: 'YouTube Kids', svg: 'assets/icons/youtubekids.svg', packages: ['com.google.android.apps.youtube.kids'] },
  /* Music and audio */
  { id: 'youtubemusic', name: 'YouTube Music', svg: 'assets/icons/youtubemusic.svg', packages: ['com.google.android.youtube.tvmusic', 'com.google.android.apps.youtube.music'] },
  { id: 'amazonmusic', name: 'Amazon Music', svg: 'assets/icons/amazonmusic.svg', packages: ['com.amazon.music.tv', 'com.amazon.mp3'] },
  { id: 'tidal', name: 'TIDAL', svg: 'assets/icons/tidal.svg', packages: ['com.aspiro.tidal'] },
  { id: 'deezer', name: 'Deezer', svg: 'assets/icons/deezer.svg', packages: ['deezer.android.app'] },
  { id: 'soundcloud', name: 'SoundCloud', svg: 'assets/icons/soundcloud.svg', packages: ['com.soundcloud.android'] },
  { id: 'plexamp', name: 'Plexamp', svg: 'assets/icons/plexamp.svg', packages: ['tv.plex.labs.plexamp'] },
  { id: 'audible', name: 'Audible', svg: 'assets/icons/audible.svg', packages: ['com.audible.application'] },
  { id: 'pocketcasts', name: 'Pocket Casts', svg: 'assets/icons/pocketcasts.svg', packages: ['au.com.shiftyjelly.pocketcasts'] },
  /* Games */
  { id: 'steam', name: 'Steam', svg: 'assets/icons/steam.svg', packages: ['com.valvesoftware.steamlink', 'com.valvesoftware.android.steam.community'] },
  { id: 'playstation', name: 'PlayStation', svg: 'assets/icons/playstation.svg', packages: ['com.playstation.remoteplay', 'com.scee.psxandroid'] },
  { id: 'xbox', name: 'Xbox', svg: 'assets/icons/xbox.svg', packages: ['com.microsoft.xboxone.smartglass'] },
  { id: 'geforcenow', name: 'GeForce NOW', svg: 'assets/icons/geforcenow.svg', packages: ['com.nvidia.geforcenow'] },
  { id: 'moonlight', name: 'Moonlight', svg: 'assets/icons/moonlight.svg', packages: ['com.limelight'] },
  /* VPN */
  { id: 'nordvpn', name: 'NordVPN', svg: 'assets/icons/nordvpn.svg', packages: ['com.nordvpn.android'] },
  { id: 'expressvpn', name: 'ExpressVPN', svg: 'assets/icons/expressvpn.svg', packages: ['com.expressvpn.vpn'] },
  { id: 'surfshark', name: 'Surfshark', svg: 'assets/icons/surfshark.svg', packages: ['com.surfshark.vpnclient.android'] },
  { id: 'protonvpn', name: 'Proton VPN', svg: 'assets/icons/protonvpn.svg', packages: ['ch.protonvpn.android'] },
  { id: 'mullvad', name: 'Mullvad', svg: 'assets/icons/mullvad.svg', packages: ['net.mullvad.mullvadvpn'] },
  { id: 'purevpn', name: 'PureVPN', svg: 'assets/icons/purevpn.svg', packages: ['com.gaditek.purevpnics'] },
  /* Utilities */
  { id: 'googleplay', name: 'Google Play', svg: 'assets/icons/googleplay.svg', packages: ['com.android.vending'] },
  { id: 'downloader', name: 'Downloader', svg: 'assets/icons/downloader.svg', packages: ['com.esaba.downloader'] },
  { id: 'xplore', name: 'X-plore', svg: 'assets/icons/xplore.svg', packages: ['com.lonelycatgames.Xplore'] },
  { id: 'airscreen', name: 'AirScreen', svg: 'assets/icons/airscreen.svg', packages: ['com.ionitech.airscreen'] }
];
