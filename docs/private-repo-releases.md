# 소스 저장소를 private으로 두고 업데이트를 유지하는 방법

앱은 GitHub에 **로그인하지 않은 채로** 최신 릴리스를 조회하고 APK를 받습니다. 그런데 private 저장소의 릴리스와 다운로드는 로그인한 사람에게만 열립니다. 그래서 소스 저장소를 private으로 바꾸면 앱 안의 자동·수동 업데이트가 멈춥니다.

그래서 **릴리스만 담는 public 저장소**(`whyitworks0415/Notesis-releases`)를 따로 두고, 빌드가 끝나면 같은 릴리스를 거기에도 올립니다. 앱은 그 저장소를 먼저 보고, 없으면 소스 저장소를 봅니다(둘 중 더 새로운 쪽이 쓰입니다).

## 처음 한 번 하는 일
1. **릴리스 전용 저장소 만들기**
   - GitHub에서 New repository → 이름 `Notesis-releases`, **Public**, "Add a README file" 체크 → Create.
   - README가 있어야 합니다. 비어 있으면 릴리스의 태그를 만들 수 없습니다.
2. **접근 토큰 만들기** (빌드가 위 저장소에 릴리스를 쓰기 위한 것)
   - GitHub → 오른쪽 위 프로필 → Settings → Developer settings → Personal access tokens → **Fine-grained tokens** → Generate new token
   - Resource owner: 본인 계정
   - Expiration: 최대 기간(1년)
   - Repository access: **Only select repositories** → `Notesis-releases` **하나만**
   - Permissions → Repository permissions → **Contents: Read and write**
   - Generate token 후 나오는 `github_pat_...` 값을 복사합니다. 이 화면을 벗어나면 다시 볼 수 없습니다.
3. **토큰을 소스 저장소의 비밀값으로 넣기**
   - `Notesis` 저장소 → Settings → Secrets and variables → Actions → New repository secret
   - Name: `RELEASES_TOKEN`, Secret: 복사한 토큰
4. 빌드를 한 번 돌립니다(Actions → APK → Run workflow). 빌드가 끝나면 `Notesis-releases`의 Releases에 같은 APK가 올라와 있어야 합니다.

## 소스 저장소를 private으로 바꾸기 전에
- **이미 설치된 앱(0.82.2 이하)은 옛 저장소만 봅니다.** 새 주소를 아는 버전(0.82.3 이상)으로 먼저 업데이트하세요. 설치된 모든 기기에서 About · Help에 0.82.3 이상이 보일 때까지 기다린 뒤 private으로 바꿉니다.
- private으로 바꾼 뒤에도 빌드는 그대로 됩니다. 릴리스는 두 저장소에 모두 올라가고, 소스 저장소 쪽은 본인만 볼 수 있습니다.
- Actions는 private 저장소에서 사용 시간 한도가 있습니다(무료 계정 월 2,000분 안팎). 빌드 한 번이 약 6분입니다.

## 토큰이 만료되면
- 만료되면 빌드의 "Publish the same release where the app looks for updates" 단계가 실패하고, 앱에는 새 릴리스가 보이지 않습니다.
- 위 2~3번을 다시 해서 새 토큰으로 `RELEASES_TOKEN`을 바꿔 주세요. 만료일을 달력에 적어 두는 것이 좋습니다.

## 같이 알아 둘 것
- 릴리스에 올라가는 APK와 릴리스 노트는 누구나 볼 수 있습니다. 소스 코드는 올라가지 않습니다.
- 소스 저장소에는 서명 키(`keystore/release.jks`)와 비밀번호가 올라가 있습니다. private으로 바꿔도 이미 공개되어 있던 기간은 지워지지 않습니다. 키를 새로 만들면 확실하지만, 서명이 달라져서 기존 설치를 지우고 다시 설치해야 합니다.
