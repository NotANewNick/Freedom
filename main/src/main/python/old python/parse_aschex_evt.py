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

import sys
import traceback
from parse_aschex_exception import *
from parse_aschex_util import *
from parse_aschex_rsp import Position

evt_mask_speed = 0x00000001
evt_mask_heading = 0x00000002
evt_mask_altitude = 0x00000004
evt_mask_cell_id = 0x00000008

evt_mask_send_t = 0x00000010
evt_mask_cn = 0x00000020
evt_mask_dev_name = 0x00000040
evt_mask_length = 0x00000080

evt_mask_dev_type = 0x00000100
evt_mask_pr_ver = 0x00000200
evt_mask_fm_ver = 0x00000400
evt_mask_battery_level = 0x00000800

evt_mask_eps_vcc = 0x00001000

evt_mask_io = 0x00020000
evt_mask_motion_status = 0x00040000
evt_mask_gps_info = 0x00080000

evt_mask_current_mileage = 0x00100000
evt_mask_total_mileage = 0x00200000
evt_mask_current_hmc = 0x00400000
evt_mask_total_hmc = 0x00800000

evt_mask_non = 0xffffffff

evt_msg_type_str = { 0: 'UNK0',   1: 'PNA',    2: 'PFA',    3: 'MPN',    4: 'MPF',
                     5: 'UNK5',   6: 'BPL',    7: 'BTC',    8: 'STC',    9: 'STT',
                    10: 'UNK10', 11: 'UNK11', 12: 'PDP',   13: 'IGN',   14: 'IGF',
                    15: 'UPD',   16: 'IDN',   17: 'IDF',   18: 'UNK18', 19: 'UNK19',
                    20: 'JDR',   21: 'GSS',   22: 'UNK22', 23: 'CRA',   24: 'UNK24',
                    25: 'DOS',   26: 'GES',   27: 'UNK27', 28: 'STR',   29: 'STP',
                    30: 'LSP',   31: 'GPJ',   32: 'RMD',   33: 'JDS',   34: 'UNK34',
                    35: 'UNK35', 36: 'UPC'}

def is_evt_bpl(msg_type):
    return evt_msg_type_str[msg_type] == 'BPL'

def is_evt_igs(msg_type):
    return evt_msg_type_str[msg_type] == 'IGN' or evt_msg_type_str[msg_type] == 'IGF'

def is_evt_upd(msg_type):
    return evt_msg_type_str[msg_type] == 'UPD'

def is_evt_idf(msg_type):
    return evt_msg_type_str[msg_type] == 'IDF'

def is_evt_gss(msg_type):
    return evt_msg_type_str[msg_type] == 'GSS'

def is_evt_cra(msg_type):
    return evt_msg_type_str[msg_type] == 'CRA'

def is_evt_dos(msg_type):
    return evt_msg_type_str[msg_type] == 'DOS'

def is_evt_ges(msg_type):
    return evt_msg_type_str[msg_type] == 'GES'

def is_evt_gpj(msg_type):
    return evt_msg_type_str[msg_type] == 'GPJ'

def is_evt_rmd(msg_type):
    return evt_msg_type_str[msg_type] == 'RMD'

def is_evt_jds(msg_type):
    return evt_msg_type_str[msg_type] == 'JDS'

def is_evt_upc(msg_type):
    return evt_msg_type_str[msg_type] == 'UPC'

#Helper Class
class GeoFence:
    def __init__(self, geo_id, geo_enable, radius, check_interval, trigger_mode):
        self.geo_id = geo_id
        self.geo_enable = geo_enable
        self.radius = radius
        self.check_interval = check_interval
        self.trigger_mode = trigger_mode

#########################################
# External Interface
#########################################
def parse_aschex_report_evt(aschex_report):
    asc_report = []
    len_parsed = 0
    mask = 0
    positions = []
    msg_arg = {}
    dbg_on = get_dbg_on()
    try:
        evt_frame = aschex_report
        #print '+EVT:', evt_frame

        len_parsed += get_header(asc_report, evt_frame[len_parsed:])[1]

        dbg_print(dbg_on, 'rsp_type: %s' % evt_frame[len_parsed:])
        rsp_type, len_field = get_msg_type(asc_report, evt_frame[len_parsed:], evt_msg_type_str)
        len_parsed += len_field
        msg_arg['msg_type'] = rsp_type

        dbg_print(dbg_on, 'mask: %s' % evt_frame[len_parsed:])
        mask, len_field = get_mask(asc_report, evt_frame[len_parsed:])
        len_parsed += len_field
        msg_arg['mask'] = mask
        #print 'mask: 0x%04X' % mask

        if mask & evt_mask_length:
            dbg_print(dbg_on, 'length: %s' % evt_frame[len_parsed:])
            rsp_length, len_field = get_length(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['length'] = rsp_length

        if mask & evt_mask_dev_type:
            dbg_print(dbg_on, 'dev type: %s' % evt_frame[len_parsed:])
            dev_type, len_field = get_dev_type(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['dev_type'] = dev_type

        if mask & evt_mask_pr_ver:
            dbg_print(dbg_on, 'pr ver: %s' % evt_frame[len_parsed:])
            pr_ver, len_field = get_protocol_version(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['pr_ver'] = pr_ver

        if mask & evt_mask_fm_ver:
            dbg_print(dbg_on, 'fm ver: %s' % evt_frame[len_parsed:])
            fm_ver, len_field = get_firmware_version(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['fm_ver'] = fm_ver

        if mask & evt_mask_dev_name:
            dbg_print(dbg_on, 'dev_name: %s' % evt_frame[len_parsed:])
            dev_name, len_field = get_dev_name(asc_report, evt_frame[len_parsed:])
            msg_arg['dev_name'] = dev_name
            uid = dev_name
        else:
            dbg_print(dbg_on, 'imei: %s' % evt_frame[len_parsed:])
            dbg_print(dbg_on, 'imei in ascii: %s' % evt_frame[len_parsed:])
            imei, len_field = get_uid(asc_report, evt_frame[len_parsed:])
            msg_arg['uid'] = imei
            uid = imei
        len_parsed += len_field

        if mask & evt_mask_battery_level:
            dbg_print(dbg_on, 'battery_level: %s' % evt_frame[len_parsed:])
            battery_level, len_field = get_battery_level(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['battery_level'] = battery_level

        if mask & evt_mask_eps_vcc:
            dbg_print(dbg_on, 'eps_vcc: %s' % evt_frame[len_parsed:])
            eps_vcc, len_field = get_eps_vcc(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['eps_vcc'] = eps_vcc

        if mask & evt_mask_io:
            dbg_print(dbg_on, 'dis: %s' % evt_frame[len_parsed:])
            dis, len_field = get_input(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['dis'] = dis
            dbg_print(dbg_on, 'dos: %s' % evt_frame[len_parsed:])
            dos, len_field = get_output(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['dos'] = dos

        if mask & evt_mask_motion_status:
            dbg_print(dbg_on, 'motion_status: %s' % evt_frame[len_parsed:])
            motion_status, len_field = get_motion_state(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['motion_status'] = motion_status

        if mask & evt_mask_gps_info:
            dbg_print(dbg_on, 'gps_info: %s' % evt_frame[len_parsed:])
            gps_scnt, len_field = get_gps_scnt(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['gps_scnt'] = gps_scnt

        if is_evt_bpl(rsp_type): #BPL
            dbg_print(dbg_on, 'BPL Arg: %s' % evt_frame[len_parsed:])
            bat_vcc, len_field = get_bat_vcc(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['bat_vcc'] = bat_vcc
        elif is_evt_igs(rsp_type): #IGS
            dbg_print(dbg_on, 'IGS Arg: %s' % evt_frame[len_parsed:])
            igs_duration, len_field = get_igs_duration(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['igs_duration'] = igs_duration
        elif is_evt_upd(rsp_type): #UPD
            dbg_print(dbg_on, 'UPD Arg: %s' % evt_frame[len_parsed:])
            (code, retry), len_field = get_upd_arg(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['upd_code'] = code
            msg_arg['upd_retry'] = retry
        elif is_evt_idf(rsp_type): #IDF
            dbg_print(dbg_on, 'IDF Arg: %s' % evt_frame[len_parsed:])
            idf_duration, len_field = get_idf_duration(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['idf_duration'] = idf_duration
        elif is_evt_gss(rsp_type): #GSS
            dbg_print(dbg_on, 'GSS Arg: %s' % evt_frame[len_parsed:])
            (gss_fix, gss_duration), len_field = get_gss_arg(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['gss_fix'] = gss_fix
            msg_arg['gss_duration'] = gss_duration
        elif is_evt_dos(rsp_type): #DOS
            dbg_print(dbg_on, 'DOS Arg: %s' % evt_frame[len_parsed:])
            (output_id, output_status), len_field = get_dos_arg(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['output_id'] = output_id
            msg_arg['output_status'] = output_status
        elif is_evt_ges(rsp_type): #GES
            dbg_print(dbg_on, 'GES Arg: %s' % evt_frame[len_parsed:])
            (geo_id, geo_enable, trigger_mode, radius, check_interval), len_field = get_ges_arg(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['geo'] = GeoFence(geo_id, geo_enable, radius, check_interval, trigger_mode)
        elif is_evt_gpj(rsp_type): #GPJ
            dbg_print(dbg_on, 'GPJ Arg: %s' % evt_frame[len_parsed:])
            (cw_jamming, gps_jamming_status), len_field = get_gpj_arg(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['cw_jamming'] = cw_jamming
            msg_arg['gps_jamming_status'] = gps_jamming_status
        elif is_evt_rmd(rsp_type): #RMD
            dbg_print(dbg_on, 'RMD Arg: %s' % evt_frame[len_parsed:])
            roaming_status, len_field = get_rmd_arg(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['roaming_status'] = roaming_status
        elif is_evt_jds(rsp_type): #JDS
            dbg_print(dbg_on, 'JDS Arg: %s' % evt_frame[len_parsed:])
            jamming_status, len_field = get_jds_arg(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['jamming_status'] = jamming_status
        elif is_evt_upc(rsp_type): #UPC
            dbg_print(dbg_on, 'UPC Arg: %s' % evt_frame[len_parsed:])
            (cmdid, code, url), len_field = get_upc_arg(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['upc_cmdid'] = cmdid
            msg_arg['upc_code'] = code
            msg_arg['upc_url'] = url
        else:
            pass

        dbg_print(dbg_on, 'point_number: %s' % evt_frame[len_parsed:])
        point_number, len_field = get_point_number(asc_report, evt_frame[len_parsed:])
        len_parsed += len_field
        msg_arg['point_number'] = point_number

        for n in range(int(point_number)):
            pos_arg = {}

            dbg_print(dbg_on, 'accuracy: %s' % evt_frame[len_parsed:])
            accuracy, len_field = get_accuracy(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            pos_arg['accuracy'] = accuracy

            if mask & evt_mask_speed:
                dbg_print(dbg_on, 'speed: %s' % evt_frame[len_parsed:])
                speed, len_field = get_speed(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['speed'] = speed

            if mask & evt_mask_heading:
                dbg_print(dbg_on, 'heading: %s' % evt_frame[len_parsed:])
                heading, len_field = get_heading(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['heading'] = heading

            if mask & evt_mask_altitude:
                dbg_print(dbg_on, 'altitude: %s' % evt_frame[len_parsed:])
                altitude, len_field = get_altitude(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['altitude'] = altitude

            dbg_print(dbg_on, 'longitude: %s' % evt_frame[len_parsed:])
            lng, len_field = get_lng(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            pos_arg['longitude'] = lng

            dbg_print(dbg_on, 'latitude: %s' % evt_frame[len_parsed:])
            lat, len_field = get_lat(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            pos_arg['latitude'] = lat

            dbg_print(dbg_on, 'gps_utc_t: %s' % evt_frame[len_parsed:])
            gps_tm, len_field = get_gps_time(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            pos_arg['gps_utc_t'] = gps_tm

            if mask & evt_mask_cell_id:
                dbg_print(dbg_on, 'mcc: %s' % evt_frame[len_parsed:])
                mcc, len_field = get_mcc(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['mcc'] = mcc
                dbg_print(dbg_on, 'mnc: %s' % evt_frame[len_parsed:])
                mnc, len_field = get_mnc(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['mnc'] = mnc
                dbg_print(dbg_on, 'lac: %s' % evt_frame[len_parsed:])
                lac, len_field = get_lac(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['lac'] = lac
                dbg_print(dbg_on, 'cell_id: %s' % evt_frame[len_parsed:])
                cell_id, len_field = get_cell_id(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['cell_id'] = cell_id
                dbg_print(dbg_on, 'ta: %s' % evt_frame[len_parsed:])
                ta, len_field = get_ta(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['ta'] = ta
            positions.append(Position(**pos_arg))
        msg_arg['positions'] = positions

        if mask & evt_mask_current_mileage:
            dbg_print(dbg_on, 'current_mileage: %s' % evt_frame[len_parsed:])
            current_mileage, len_field = get_current_mileage(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['current_mileage'] = current_mileage

        if mask & evt_mask_total_mileage:
            dbg_print(dbg_on, 'total_mileage: %s' % evt_frame[len_parsed:])
            total_mileage, len_field = get_total_mileage(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['total_mileage'] = total_mileage

        if mask & evt_mask_current_hmc:
            dbg_print(dbg_on, 'current_hmc: %s' % evt_frame[len_parsed:])
            current_hmc, len_field = get_current_hmc(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['current_hmc'] = current_hmc

        if mask & evt_mask_total_hmc:
            dbg_print(dbg_on, 'total_hmc: %s' % evt_frame[len_parsed:])
            total_hmc, len_field = get_total_hmc(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['total_hmc'] = total_hmc

        if mask & evt_mask_send_t:
            send_t, len_field = get_send_time(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['send_t'] = send_t

        if mask & evt_mask_cn:
            cn, len_field = get_count_number(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['cn'] = cn
        else:
            msg_arg['cn'] = None

        crc16, len_field = get_checksum(asc_report, evt_frame[len_parsed:])
        len_parsed += len_field
        msg_arg['crc16'] = crc16

        checksum = verify_crc16(evt_frame[len_header*len_byte:len_parsed], crc16)
        msg_arg['checksum'] = checksum

        tail, len_field = get_tail(asc_report, evt_frame[len_parsed:])
        len_parsed += len_field
        if tail.upper() != '0D0A':
            print ('Fail to verify tail: %s' % tail)
            raise ParseTailError

        if mask & evt_mask_length and rsp_length*len_byte != len_parsed:
            print ('Length does not match: Report: %d != Actual: %d' % (rsp_length, len_parsed/len_byte))
            raise ParseLengthError

        msg_arg['asc_report'] = asc_report
    except:
        print ('-'*40)
        print ('Parse Hex EVT format error: ', sys.exc_info()[0])
        print (aschex_report)
        print (asc_report)
        traceback.print_exc() # XXX But this goes to stderr!
        print ('-'*40)
        raise ParseFormatError
    return msg_arg, len_parsed
