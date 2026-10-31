cmake_minimum_required(VERSION 3.22.1)
project("hifi_usb_engine" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_compile_options(-O3 -Wall -Wextra -fno-exceptions)

set(LIBUSB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/external/libusb/libusb)

add_library(usb1.0 STATIC
    ${LIBUSB_DIR}/core.c
    ${LIBUSB_DIR}/descriptor.c
    ${LIBUSB_DIR}/hotplug.c
    ${LIBUSB_DIR}/io.c
    ${LIBUSB_DIR}/sync.c
    ${LIBUSB_DIR}/strerror.c
    ${LIBUSB_DIR}/os/events_posix.c
    ${LIBUSB_DIR}/os/threads_posix.c
    ${LIBUSB_DIR}/os/linux_usbfs.c
    ${LIBUSB_DIR}/os/linux_netlink.c
)

target_include_directories(usb1.0 PUBLIC
    ${LIBUSB_DIR}
    ${LIBUSB_DIR}/os
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_definitions(usb1.0 PRIVATE
    OS_LINUX=1
    _GNU_SOURCE=1
    HAVE_CLOCK_GETTIME=1
    DEFAULT_VISIBILITY=__attribute__\(\(visibility\("default"\)\)\)
)

add_library(hifi_usb_engine SHARED native-uac-driver.cpp)
target_include_directories(hifi_usb_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${LIBUSB_DIR})

find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(hifi_usb_engine usb1.0 ${log-lib} ${android-lib})
