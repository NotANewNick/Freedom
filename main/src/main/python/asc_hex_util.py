################################################################
# Copyright (C) 2017 Queclink Wirless Solutions Co., Ltd.
# All Rights Reserved
#
# This code is provided by Queclink to its customers as a
# sample to demonstrate how to how to parse HEX format
# @Track protocol messages.
#
# Permission to use, copy and modify this code for the purpose
# of use or test Queclink devices is hereby granted, provided
# that the above copyright notice, this paragraph and the
# following paragraph appear in all copies, modifications.
#
# You must not distribute this code to others without express
# authority from Queclink.
################################################################

import os
import sys
import struct
from crcmod import crcmod
# from parse_aschex_exception import *
from bitstring import BitStream

get_crc16 = crcmod.mkCrcFun(0x11021, rev=False)

len_byte = 2
len_header = 4
len_msg_type = 1
len_mask = 4
len_length = 2
len_dev_type = 1
len_pr_ver = 2
len_fm_ver = 2
len_uid = 8
len_dev_name = 8
len_battery_level = 1
len_eps_vcc = 2
len_input = 1
len_output = 1
len_motion_state = 1
len_gps_info = 1
len_gps_scnt = 1
len_id_type = 1
len_number = 1
len_accuracy = 1
len_speed = 3
len_speed_int = 2
len_speed_frac = 1
len_heading = 2
len_altitude = 2
len_lng = 4
len_lat = 4
len_utc_t = 7
len_mcc = 2
len_mnc = 2
len_lac = 2
len_cell_id = 2
len_ta = 1
len_current_mileage = 3
len_total_mileage = 5
len_current_mileage_int = 2
len_total_mileage_int = 4
len_mileage_frac = 1
len_current_hmc = 3
len_total_hmc = 6
len_current_hmc_hh = 1
len_total_hmc_hh = 4
len_hmc_mm = 1
len_hmc_ss = 1
len_send_t = 7
len_cn = 2
len_crc = 2
len_tail = 2

def get_reserved_para(asc_report, aschex, num):
    #asc_report.append(aschex[:num*len_byte])
    return num * len_byte

def get_tail(asc_report, aschex):
    tail = aschex[:len_tail*len_byte]
    return tail, len_tail * len_byte

def verify_crc16(aschex, checksum):
    len_aschex = len(aschex) - len_crc * len_byte
    #print aschex[:len_aschex]
    print(aschex,checksum)
    print('#################################')

    hexstr = ''.join([chr(int(aschex[i : i+len_byte], 16)) for i in range(0, len_aschex, len_byte)])
    print(hexstr)
    crc16 = get_crc16(hexstr)
    print(crc16)
    print('#################################')
    return 'Verify CRC16 %s: 0x%04X <=> 0x%04X' % ({True:'OK', False:'Fail'}[crc16 == checksum], crc16, checksum)

def get_checksum(asc_report, aschex):
    crc16 = aschex[:len_crc*len_byte]
    print(crc16)
    asc_report.append(crc16)
    return int(crc16, 16), len_crc * len_byte

def get_count_number(asc_report, aschex):
    cn = aschex[:len_cn*len_byte]
    asc_report.append(cn)
    return cn, len_cn * len_byte

def get_send_time(asc_report, aschex):
    print(asc_report,'asc_report')
    print(aschex,'asc_hex')
    send_time_bytes = bytes.fromhex(aschex)  # Convert the hexadecimal string to bytes
    print(send_time_bytes,'henlo')
    year = aschex[:8]
    print(year,'<------')
    month = aschex[8:10]
    print(month, '<------')
    day = aschex[10:12]
    hour = aschex[12:14]
    minute = aschex[14:16]
    second = aschex[16:18]
    year = int(year,16)
    month = int(month,16)
    day = int(day,16)
    hour = int(hour,16)
    minute = int(minute,16)
    second = int(second,16)
    print(year,'should be 2013')
    print(month,'should be xx')
    send_time = ['send time',year,month,day,hour,minute,second]

    asc_report.append(send_time)
    print('Send Time: ', send_time)
    return send_time, len_send_t * len_byte

def get_send_time_hbd(asc_report, aschex):
    print(asc_report,'asc_report')
    print(aschex,'asc_hex')
    send_time_bytes = bytes.fromhex(aschex)  # Convert the hexadecimal string to bytes
    print(send_time_bytes,'henlo')
    year = aschex[:4]
    print(year,'<------')
    month = aschex[4:6]
    print(month, '<------')
    day = aschex[6:8]
    hour = aschex[8:10]
    minute = aschex[10:12]
    second = aschex[12:14]
    year = int(year,16)
    month = int(month,16)
    day = int(day,16)
    hour = int(hour,16)
    minute = int(minute,16)
    second = int(second,16)
    print(year,'should be 2013')
    print(month,'should be xx')
    send_time = ['send time',year,month,day,hour,minute,second]

    asc_report.append(send_time)
    print('Send Time: ', send_time)
    return send_time, len_send_t * len_byte

def get_total_hmc(asc_report, aschex):
    print(aschex,"______")
    hmc_hh = '%d' % int(aschex[:len_total_hmc_hh*len_byte], 16)
    len_parsed = len_total_hmc_hh * len_byte
    hmc_mm = '%02d' % int(aschex[len_parsed:(len_parsed+len_hmc_mm*len_byte)], 16)
    len_parsed += len_hmc_mm * len_byte
    hmc_ss = '%02d' % int(aschex[len_parsed:(len_parsed+len_hmc_ss*len_byte)], 16)
    len_parsed += len_hmc_ss * len_byte
    total_hmc = '.'.join([hmc_hh, hmc_mm, hmc_ss])
    asc_report.append(total_hmc)
    print("HMC:", total_hmc)
    return total_hmc, len_parsed

def get_current_hmc(asc_report, aschex):
    hmc_hh = '%02d' % int(aschex[:len_current_hmc_hh*len_byte], 16)
    len_parsed = len_current_hmc_hh * len_byte
    hmc_mm = '%02d' % int(aschex[len_parsed:(len_parsed+len_hmc_mm*len_byte)], 16)
    len_parsed += len_hmc_mm * len_byte
    hmc_ss = '%02d' % int(aschex[len_parsed:(len_parsed+len_hmc_ss*len_byte)], 16)
    len_parsed += len_hmc_ss * len_byte
    current_hmc = '.'.join([hmc_hh, hmc_mm, hmc_ss])
    asc_report.append(current_hmc)
    print("Current HMC:", current_hmc)
    return current_hmc, len_parsed

def get_total_mileage(asc_report, aschex):
    total_mileage_hexstr = ''.join([chr(int(aschex[i : i+len_byte], 16)) for i in range(0, len_total_mileage * len_byte, len_byte)])
    total_mileage_bytes = bytes.fromhex(aschex)
    total_mileage = '%d.%d' % struct.unpack_from('!IB', total_mileage_bytes)
    asc_report.append(total_mileage)
    print("Total Mileage:", total_mileage)
    return total_mileage, len_total_mileage * len_byte

def get_current_mileage(asc_report, aschex):
    current_mileage_hexstr = ''.join([chr(int(aschex[i : i+len_byte], 16)) for i in range(0, len_current_mileage * len_byte, len_byte)])
    current_mileage_bytes = bytes.fromhex(aschex)
    current_mileage = '%d.%d' % struct.unpack_from('!HB', current_mileage_bytes)
    asc_report.append(current_mileage)
    print("Current Mileage:", current_mileage)
    return current_mileage, len_current_mileage * len_byte

def get_ta(asc_report, aschex):
    ta = aschex[:len_ta*len_byte]
    print("TA :",ta)
    asc_report.append(ta)
    return ta, len_ta * len_byte

def get_cell_id(asc_report, aschex):
    cell_id = aschex[:len_cell_id*len_byte]
    asc_report.append(cell_id)
    print("Cell ID:", cell_id)
    return cell_id, len_cell_id * len_byte

def get_lac(asc_report, aschex):
    lac = aschex[:len_lac*len_byte]
    asc_report.append(lac)
    print("LAC :",lac)
    return lac, len_lac * len_byte

def get_mnc(asc_report, aschex):
    mnc = aschex[:len_mnc*len_byte]
    asc_report.append(mnc)
    print("MNC :", mnc)
    return mnc, len_mnc * len_byte

def get_mcc(asc_report, aschex):
    mcc = aschex[:len_mcc*len_byte]
    asc_report.append(mcc)
    print("MCC :",mcc)
    return mcc, len_mcc * len_byte

def get_gps_time(asc_report, aschex):
    gps_time_bytes = bytes.fromhex(aschex)
    print(aschex)
    print("/"*10,gps_time_bytes)
    gps_time = '%d%02d%02d%02d%02d%02d' % struct.unpack_from('!HBBBBB', gps_time_bytes)
    print("GPS Time: ",gps_time)
    asc_report.append(gps_time)
    return gps_time, len_utc_t * len_byte

def get_lnglat_string(lnglat):
    precision = 1000000
    if lnglat >= 0:
        lnglat_str = '%d.%06d' % ((lnglat / precision), lnglat % precision)
    else:
        lnglat = abs(lnglat)
        lnglat_str = '-%d.%06d' % ((lnglat / precision), lnglat % precision)
    print(lnglat_str,"this is the long lat string")
    return lnglat_str

def get_lat(asc_report, aschex):
    lat_hexstr = ''.join([chr(int(aschex[i : i+len_byte], 16)) for i in range(0, len_lat * len_byte, len_byte)])
    lat_bytes = bytes.fromhex(aschex)
    lat = get_lnglat_string(struct.unpack_from('!i', lat_bytes)[0])
    asc_report.append(lat)
    print("Latitude : ",lat)
    return lat, len_lat * len_byte

def get_lng(asc_report, aschex):
    lng_hexstr = ''.join([chr(int(aschex[i : i+len_byte], 16)) for i in range(0, len_lng * len_byte, len_byte)])
    lng_bytes = bytes.fromhex(aschex)
    lng = get_lnglat_string(struct.unpack_from('!i', lng_bytes)[0])
    print("Longitude :",lng)
    asc_report.append(lng)
    return lng, len_lng * len_byte

def get_altitude(asc_report, aschex):
    # altitude_hexstr = ''.join([chr(int(aschex[i : i+len_byte], 16)) for i in range(0, len_altitude * len_byte, len_byte)])
    # altitude = '%d' % struct.unpack('!h', altitude_hexstr)[0]
    altitude_bytes = bytes.fromhex(aschex)  # Convert the hexadecimal string to bytes
    print(altitude_bytes)
    altitude = struct.unpack_from('!h', altitude_bytes)
    altitude = str(altitude)
    print("Altitude:",altitude)
    asc_report.append(altitude)
    return altitude, len_altitude * len_byte

def get_heading(asc_report, aschex):
    heading = '%d' % int(aschex[:len_heading*len_byte], 16)
    asc_report.append(heading)
    print("Azimuth/heading", heading)
    return heading, len_heading * len_byte

def get_speed(asc_report, aschex):
    speed_int = int(aschex[:len_speed_int*len_byte], 16)
    speed_frac = int(aschex[len_speed_int*len_byte : len_speed*len_byte], 16)
    speed = '%d.%d' % (speed_int, speed_frac)
    asc_report.append(speed)
    print("Speed :", speed)
    return speed, len_speed * len_byte

def get_accuracy(asc_report, aschex):
    accuracy = '%d' % int(aschex[:len_accuracy*len_byte], 16)
    asc_report.append(accuracy)
    print("Accuracy:", accuracy)
    return accuracy, len_accuracy * len_byte

def get_phone_number(asc_report, aschex):
    len_number_length_type = 1
    length_type_str = aschex[:len_number_length_type*len_byte]
    #high nibble is length
    len_phone_number = int(length_type_str[0], 16)
    #low nibble is the sign of the phone
    phone_type = {'0':'', '1':'+'}[length_type_str[1]]
    #phone numbers
    phone_number = phone_type + aschex[len_number_length_type*len_byte:len_phone_number*len_byte].upper().split('F')[0]
    asc_report.append(phone_number)
    print ('phone number:', phone_number)
    return phone_number, len_phone_number*len_byte

def get_gps_scnt(asc_report, aschex):
    gps_scnt = int(aschex[:len_gps_scnt*len_byte], 16)
    asc_report.append('%d' % gps_scnt)
    print("GPS scnt : ",gps_scnt)
    return gps_scnt, len_gps_scnt * len_byte

def get_gps_info(asc_report, aschex):
    gps_info = aschex[:len_gps_info*len_byte]
    gps_antenna = int(gps_info[0], 16)
    gps_scnt = int(gps_info[1], 16)
    print("GPS general info", gps_antenna, gps_scnt)
    asc_report.append('%d,%d' % str((gps_antenna, gps_scnt)))
    return (gps_antenna, gps_scnt), len_gps_info * len_byte

def get_motion_state(asc_report, aschex):
    motion_state = aschex[:len_motion_state*len_byte]
    asc_report.append(motion_state)
    print("Motion State:",motion_state)
    return motion_state, len_motion_state * len_byte



def get_eps_vcc(asc_report, aschex):
    eps_vcc = '%d' % int(aschex[:len_eps_vcc*len_byte], 16)
    asc_report.append(eps_vcc)
    return eps_vcc, len_eps_vcc * len_byte

def get_dev_name(asc_report, aschex):
    aschex_dev_name = aschex[:len_dev_name * len_byte].rsplit('00')[0]
    dev_name = ''.join([chr(int(aschex_dev_name[i : i+len_byte], 16)) for i in range(0, len(aschex_dev_name), len_byte)])
    asc_report.append(dev_name)
    return dev_name, len_dev_name * len_byte

def get_uid(asc_report, aschex):
    uid = [int(aschex[i : i+len_byte], 16) for i in range(0, len_uid * len_byte, len_byte)]
    uid = '%02d%02d%02d%02d%02d%02d%02d%d' % tuple(uid)
    asc_report.append(uid)
    print("UID:", uid)
    return uid, len_uid * len_byte


def get_firmware_version(asc_report, aschex):
    fm_ver = aschex[:len_fm_ver*len_byte]
    asc_report.append(fm_ver)
    print("Firmware Version :", fm_ver)
    return fm_ver, len_fm_ver * len_byte


def get_protocol_version(asc_report, aschex):
    pr_ver = aschex[:len_pr_ver*len_byte]
    asc_report.append(pr_ver)
    print("Protocol Version:", pr_ver)
    return pr_ver, len_pr_ver * len_byte


def get_dev_type(asc_report, aschex):
    dev_type = aschex[:len_dev_type*len_byte]
    asc_report.append(dev_type)
    print("Device Type:", dev_type)
    return dev_type, len_dev_type * len_byte


def get_length(asc_report, aschex):
    length = int(aschex[:len_length*len_byte], 16)
    asc_report.append('%d' % length)
    print("length of message :", length)
    return length, len_length * len_byte


def get_mask(asc_report, aschex):
    mask = int(aschex[:len_mask*len_byte], 16)
    asc_report.append(aschex[:len_mask*len_byte])
    return mask, len_mask * len_byte


def get_msg_type(asc_report, aschex, header_str):
    msg_type = int(aschex[:len_msg_type*len_byte], 16)
    print(header_str)
    print(header_str[int(msg_type)],'debugging')
    asc_report.append('%d(%s)' % (msg_type, header_str[msg_type]))
    print("Message type:", msg_type)
    return msg_type, len_msg_type * len_byte


def get_header(asc_report, aschex):
    header = ''.join([chr(int(aschex[i : i+len_byte], 16)) for i in range(0, len_header * len_byte, len_byte)])
    asc_report.append(header)
    print("Header:", header)
    return header, len_header * len_byte


#####################
# CRD Specific
#####################
len_crd_mask = 2
len_crd_data_type = 1
len_crd_frame_total = 1
len_crd_frame_index = 1
len_crd_xyz_data = 500


def get_crd_mask(asc_report, aschex):
    len_parsed = len_crd_mask * len_byte
    mask = int(aschex[:len_parsed], 16)
    asc_report.append(aschex[:len_parsed])
    return mask, len_parsed


def get_crd_data_type(asc_report, aschex):
    len_parsed = len_crd_data_type * len_byte
    data_type = int(aschex[:len_parsed], 16)
    asc_report.append('%d' % data_type)
    return data_type, len_parsed

def get_crd_frame_info(asc_report, aschex):
    len_parsed = len_crd_frame_total * len_byte
    frame_total = int(aschex[:len_parsed], 16)
    frame_index = int(aschex[len_parsed:len_parsed+len_crd_frame_index*len_byte], 16)
    len_parsed += len_crd_frame_index * len_byte
    asc_report.append('%d' % frame_total)
    asc_report.append('%d' % frame_index)
    return (frame_total, frame_index), len_parsed

def get_crd_xyz_data(asc_report, aschex):
    len_parsed = len_crd_xyz_data * len_byte
    xyz_data = aschex[:len_parsed]
    asc_report.append(xyz_data)
    return xyz_data, len_parsed


#####################
# ACC Specific
#####################
acc_coord_group = 3
acc_coord_num = 75
len_acc_coord = 2
len_acc_coord_group = len_acc_coord * acc_coord_group


def get_acc_xyz_data(asc_report, aschex):
    xyz_data = []
    for i in range(acc_coord_num):
        aschex_acc_coord_group = aschex[i * len_acc_coord_group * len_byte : (i+1) * len_acc_coord_group * len_byte]
        coord_group = []
        for j in range(acc_coord_group):
            aschex_acc_coord = aschex_acc_coord_group[j * len_acc_coord * len_byte : (j+1) * len_acc_coord * len_byte]
            acc_coord_hexstr = ''.join([chr(int(aschex_acc_coord[x : x+len_byte] ,16)) for x in range(0, len_acc_coord * len_byte, len_byte)])
            coord = '%d' % struct.unpack('!h', acc_coord_hexstr)[0]
            coord_group.append(coord)
        asc_coord_group = ':'.join(coord_group)
        asc_report.append(asc_coord_group)
        xyz_data.append(acc_coord_group)
    return xyz_data, len_acc_coord_group * len_byte * acc_coord_num

#####################
# INF Specific
#####################
len_inf_mask = 2
len_hw_ver = 2
len_mcu_ver = 2
len_gps_ver = 2
len_gps_flag = 1
len_hdop = 1
len_fri_no_fix = 1
len_report_item_mask = 2
len_fri_report_mask = 2
len_bat_flag = 1
len_main_vcc = 2
len_iccid = 10
len_rssi = 1
len_ber = 1
len_tmz_flag = 1
len_tmz_offset_hour = 1
len_tmz_offset_min = 1
len_igs_interval = 2
len_gir_type = 1
len_gir_cell_num = 1
len_rx_level = 1

def get_inf_mask(asc_report, aschex):
    mask = int(aschex[:len_inf_mask * len_byte], 16)
    asc_report.append(aschex[:len_inf_mask * len_byte])
    return mask, len_inf_mask * len_byte

def get_rx_level(asc_report, aschex):
    rx = int(aschex[:len_rx_level*len_byte], 16)
    asc_report.append('%d' % rx)
    return rx, len_rx_level * len_byte

def get_gir_type(asc_report, aschex, gir_type_str):
    gir_type = int(aschex[:len_gir_type*len_byte], 16)
    asc_report.append('%d(%s)' % (gir_type, gir_type_str[gir_type]))
    return gir_type, len_gir_type * len_byte

def get_gir_cell_num(asc_report, aschex):
    cell_num = int(aschex[:len_gir_cell_num*len_byte], 16)
    asc_report.append('%d' % cell_num)
    return cell_num, len_gir_cell_num * len_byte

def get_igs_interval(asc_report, aschex):
    igs_interval = int(aschex[:len_igs_interval*len_byte], 16)
    asc_report.append('%d' % igs_interval)
    return igs_interval, len_igs_interval * len_byte

def get_tmz_offset(asc_report, aschex):
    hour = '%d' % int(aschex[:len_tmz_offset_hour*len_byte], 16)
    min = '%d' % int(aschex[len_tmz_offset_hour*len_byte : (len_tmz_offset_hour + len_tmz_offset_min)*len_byte], 16)
    asc_report.append(hour+min)
    return hour+min, (len_tmz_offset_hour + len_tmz_offset_min) * len_byte

def get_tmz_flag(asc_report, aschex):
    tmz_flag = aschex[:len_tmz_flag*len_byte]
    asc_report.append(tmz_flag)
    return tmz_flag, len_tmz_flag * len_byte

def get_ber(asc_report, aschex):
    ber = '%d' % int(aschex[:len_ber*len_byte], 16)
    asc_report.append(ber)
    return ber, len_ber * len_byte

def get_rssi(asc_report, aschex):
    rssi = '%d' % int(aschex[:len_rssi*len_byte], 16)
    asc_report.append(rssi)
    return rssi, len_rssi * len_byte

def get_iccid(asc_report, aschex):
    iccid = aschex[:len_iccid*len_byte]
    asc_report.append(iccid)
    return iccid, len_iccid * len_byte

def get_main_vcc(asc_report, aschex):
    main_vcc = '%d' % int(aschex[:len_main_vcc*len_byte], 16)
    asc_report.append(main_vcc)
    return main_vcc, len_main_vcc * len_byte

def get_bat_flag(asc_report, aschex):
    bat_flag = aschex[:len_bat_flag*len_byte]
    asc_report.append(bat_flag)
    return bat_flag, len_bat_flag * len_byte

def get_fri_report_mask(asc_report, aschex):
    fri_report_mask = aschex[:len_fri_report_mask*len_byte]
    asc_report.append(fri_report_mask)
    return fri_report_mask, len_fri_report_mask * len_byte

def get_report_item_mask(asc_report, aschex):
    report_item_mask = aschex[:len_report_item_mask*len_byte]
    asc_report.append(report_item_mask)
    return report_item_mask, len_report_item_mask * len_byte

def get_fri_no_fix(asc_report, aschex):
    fri_no_fix = int(aschex[:len_fri_no_fix*len_byte], 16)
    asc_report.append('%d' % fri_no_fix)
    return fri_no_fix, len_fri_no_fix * len_byte

def get_hdop(asc_report, aschex):
    hdop = int(aschex[:len_hdop*len_byte], 16)
    asc_report.append('%d' % hdop)
    return hdop, len_hdop * len_byte

def get_last_fix_utc(asc_report, aschex):
    return get_gps_time(asc_report, aschex)

def get_gps_flag(asc_report, aschex):
    gps_flag = aschex[:len_gps_flag*len_byte]
    asc_report.append(gps_flag)
    return gps_flag, len_gps_flag * len_byte

def get_gps_version(asc_report, aschex):
    gps_ver = aschex[:len_gps_ver*len_byte]
    asc_report.append(gps_ver)
    return gps_ver, len_gps_ver * len_byte

def get_mcu_version(asc_report, aschex):
    mcu_ver = aschex[:len_mcu_ver*len_byte]
    asc_report.append(mcu_ver)
    return mcu_ver, len_mcu_ver * len_byte

def get_hardware_version(asc_report, aschex):
    hw_ver = aschex[:len_hw_ver*len_byte]
    asc_report.append(hw_ver)
    return hw_ver, len_hw_ver * len_byte

#####################
# RSP Specific
#####################
rsp_mask_gex_group_mask = [0x01, 0x02]
len_gex_area_type = 1
len_gex_group_mask = 1
len_gex_group = 8

def get_gex_group(asc_report, aschex):
    len_parsed = len_gex_area_type * len_byte
    gex_area_type = int(aschex[:len_parsed], 16)
    gex_group_mask = int(aschex[len_parsed:(len_parsed+len_gex_group_mask*len_byte)], 16)
    len_parsed += len_gex_group_mask * len_byte
    gex_group = []
    for mask in rsp_mask_gex_group_mask:
        if gex_group_mask & mask:
            gex_group.append(aschex[len_parsed:(len_parsed+len_gex_group*len_byte)])
            len_parsed += len_gex_group * len_byte
        else:
            gex_group.append('')
    asc_report.append('%d' % gex_area_type)
    asc_report.append('%02X' % gex_group_mask)
    asc_report.append('%s' % ','.join(gex_group))
    return (gex_area_type, gex_group_mask, gex_group), len_parsed

def get_point_number(asc_report, aschex):
    point_number = int(aschex[:len_number*len_byte], 16)
    asc_report.append('%d' % point_number)
    return point_number, len_number * len_byte

def get_lbc_phone(asc_report, aschex):
    return get_phone_number(asc_report, aschex)

def get_report_id_type(asc_report, aschex):
    id_type = aschex[:len_id_type*len_byte]
    asc_report.append(id_type)
    rsp_id = id_type[0]
    rsp_type = id_type[1]
    return (rsp_id, rsp_type), len_id_type * len_byte

#####################
# EVT Specific
#####################
len_bat_vcc = 2
len_ant_status = 1
len_igs_duration = 4
len_idf_duration = 4
len_upd_code = 2
len_upd_retry = 1
len_gss_fix = 1
len_gss_duration = 4
len_dos_output_id = 1
len_dos_output_status = 1
len_ges_id = 2
len_ges_enable = 1
len_ges_trigger_mode = 1
len_ges_radius = 4
len_ges_check_interval = 4
len_cw_jamming = 1
len_gps_jamming_status = 1
len_roaming_status = 1
len_jamming_status = 1
len_upc_cmdid = 1
len_upc_code = 2
len_upc_url_max = 100

def get_cstring(asc_report, aschex, max_length):
    len_max = min(max_length * len_byte, len(aschex))
    cstring_stream = BitStream('0x'+aschex[:len_max])
    pos = cstring_stream.find('0x00', bytealigned=True)
    if pos:
        cstring = cstring_stream[:pos[0]].bytes
    else:
        cstring = ''
    asc_report.append(cstring)
    return cstring, (len(cstring)+1)*len_byte

def get_upc_arg(asc_report, aschex):
    len_parsed = len_upc_cmdid * len_byte
    cmdid = '%d' % int(aschex[:len_parsed], 16)
    code = '%d' % int(aschex[len_parsed:len_parsed+len_upc_code*len_byte], 16)
    len_parsed += len_upc_code * len_byte
    asc_report.append(cmdid)
    asc_report.append(code)
    url, len_field = get_cstring(asc_report, aschex[len_parsed:], len_upc_url_max)
    len_parsed += len_field
    return (cmdid, code, url), len_parsed

def get_jds_arg(asc_report, aschex):
    len_parsed = len_jamming_status * len_byte
    jamming_status = int(aschex[:len_parsed], 16)
    asc_report.append('%d' % jamming_status)
    return jamming_status, len_parsed

def get_rmd_arg(asc_report, aschex):
    len_parsed = len_roaming_status * len_byte
    roaming_status = int(aschex[:len_parsed], 16)
    asc_report.append('%d' % roaming_status)
    return roaming_status, len_parsed

def get_gpj_arg(asc_report, aschex):
    len_parsed = len_cw_jamming * len_byte
    cw_jamming = int(aschex[:len_parsed], 16)
    gps_jamming_status = int(aschex[len_parsed:len_parsed+len_gps_jamming_status*len_byte], 16)
    len_parsed += len_gps_jamming_status * len_byte
    asc_report.append('%d' % cw_jamming)
    asc_report.append('%d' % gps_jamming_status)
    return (cw_jamming, gps_jamming_status), len_parsed

def get_ges_arg(asc_report, aschex):
    geo_id = int(aschex[:len_ges_id*len_byte], 16)
    len_parsed = len_ges_id * len_byte
    geo_enable = int(aschex[len_parsed:(len_parsed+len_ges_enable*len_byte)], 16)
    len_parsed += len_ges_enable * len_byte
    trigger_mode = int(aschex[len_parsed:len_parsed+len_ges_trigger_mode*len_byte], 16)
    len_parsed += len_ges_trigger_mode * len_byte
    radius = int(aschex[len_parsed:len_parsed+len_ges_radius*len_byte], 16)
    len_parsed += len_ges_radius * len_byte
    check_interval = int(aschex[len_parsed:len_parsed+len_ges_check_interval*len_byte], 16)
    len_parsed += len_ges_check_interval * len_byte
    asc_report.append('%d' % geo_id)
    asc_report.append('%d' % geo_enable)
    asc_report.append('%d' % trigger_mode)
    asc_report.append('%d' % radius)
    asc_report.append('%d' % check_interval)
    return (geo_id, geo_enable, trigger_mode, radius, check_interval), len_parsed

def get_dos_arg(asc_report, aschex):
    output_id = int(aschex[:len_dos_output_id*len_byte], 16)
    len_parsed = len_dos_output_id * len_byte
    output_status = int(aschex[len_parsed:len_parsed+len_dos_output_status*len_byte], 16)
    len_parsed += len_dos_output_status * len_byte
    asc_report.append('%d' % output_id)
    asc_report.append('%d' % output_status)
    return (output_id, output_status), len_parsed

def get_gss_arg(asc_report, aschex):
    gss_fix = int(aschex[:len_gss_fix*len_byte], 16)
    len_parsed = len_gss_fix * len_byte
    gss_duration = int(aschex[len_parsed:len_parsed+len_gss_duration*len_byte], 16)
    len_parsed += len_gss_duration * len_byte
    asc_report.append('%d' % gss_fix)
    asc_report.append('%08X' % gss_duration)
    return (gss_fix, gss_duration), len_parsed

def get_idf_duration(asc_report, aschex):
    duration = '%d' % int(aschex[:len_idf_duration*len_byte], 16)
    asc_report.append(duration)
    return duration, len_idf_duration * len_byte

def get_upd_arg(asc_report, aschex):
    code = '%d' % int(aschex[:len_upd_code*len_byte], 16)
    len_parsed = len_upd_code * len_byte
    retry = '%d' % int(aschex[len_parsed:len_parsed+len_upd_retry*len_byte], 16)
    len_parsed += len_upd_retry * len_byte
    asc_report.append(code)
    asc_report.append(retry)
    return (code, retry), len_parsed

def get_igs_duration(asc_report, aschex):
    duration = '%d' % int(aschex[:len_igs_duration*len_byte], 16)
    asc_report.append(duration)
    return duration, len_igs_duration * len_byte

def get_ant_status(asc_report, aschex):
    ant_status = aschex[:len_ant_status*len_byte]
    asc_report.append(ant_status)
    return ant_status, len_ant_status * len_byte

def get_bat_vcc(asc_report, aschex):
    bat_vcc = '%d' % int(aschex[:len_bat_vcc*len_byte], 16)
    asc_report.append(bat_vcc)
    return bat_vcc, len_bat_vcc * len_byte

#####################
# HBD Specific
#####################
len_hbd_mask = 1
len_hbd_length = 1

def get_hbd_length(asc_report, aschex):
    length = int(aschex[:len_hbd_length*len_byte], 16)
    asc_report.append('%d' % length)
    return length, len_hbd_length * len_byte

def get_hbd_mask(asc_report, aschex):
    mask = int(aschex[:len_hbd_mask * len_byte], 16)
    asc_report.append(aschex[:len_hbd_mask * len_byte])
    return mask, len_hbd_mask * len_byte

#####################
# ACK Specific
#####################
len_ack_mask = 1
len_ack_length = 1
len_ack_sub_id = 1
len_ack_sn = 2

def get_ack_serial_number(asc_report, aschex):
    sn = aschex[:len_ack_sn*len_byte]
    asc_report.append(sn)
    return sn, len_ack_sn * len_byte

def get_ack_sub_id(asc_report, aschex, sub_id_str):
    sub_id = int(aschex[:len_ack_sub_id*len_byte], 16)
    if sub_id_str:
        asc_report.append('%d(%s)' % (sub_id, sub_id_str[sub_id]))
    else:
        asc_report.append('%d' % sub_id)
    return sub_id, len_ack_sub_id * len_byte

def get_ack_length(asc_report, aschex):
    length = int(aschex[:len_ack_length*len_byte], 16)
    asc_report.append('%d' % length)
    return length, len_ack_length * len_byte

def get_ack_mask(asc_report, aschex):
    mask = int(aschex[:len_ack_mask * len_byte], 16)
    asc_report.append(aschex[:len_ack_mask * len_byte])
    return mask, len_ack_mask * len_byte

#####################
# Parameter Specific
#####################
dbg_flag = False

def set_parser_para(dbg_on):
    global dbg_flag
    dbg_flag = dbg_on

#####################
# DBG Specific
#####################

dbg_flag = False

def set_parser_para(dbg_on):
    global dbg_flag
    dbg_flag = dbg_on

def get_dbg_on():
    return dbg_flag

def dbg_print(dbg_on, dbg_msg):
    if dbg_on:
        print (dbg_msg)
