# for evt messages

import sys
import traceback
# from parse_aschex_exception import *
from asc_hex_util import *
from rsp_handler import Position

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

evt_msg_type_str = { 0: 'UNK0',3:'MPN' ,35: 'VGN', 36: 'VGF'}

class GeoFence:
    def __init__(self, geo_id, geo_enable, radius, check_interval, trigger_mode):
        self.geo_id = geo_id
        self.geo_enable = geo_enable
        self.radius = radius
        self.check_interval = check_interval
        self.trigger_mode = trigger_mode

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



        if mask & evt_mask_eps_vcc:
            dbg_print(dbg_on, 'eps_vcc: %s' % evt_frame[len_parsed:])
            eps_vcc, len_field = get_eps_vcc(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['eps_vcc'] = eps_vcc



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



        dbg_print(dbg_on, 'point_number: %s' % evt_frame[len_parsed:])
        point_number, len_field = get_point_number(asc_report, evt_frame[len_parsed:])
        point_number = 1
        len_parsed += len_field
        print(point_number,'point')
        msg_arg['point_number'] = point_number

        # put ignition handling here
        duration_of_ignition = asc_report[len_parsed:12]
        print("Duration of Ignition",duration_of_ignition)
        msg_arg["DOI"] = duration_of_ignition
        len_parsed += 12


        for n in range(int(point_number)):
            pos_arg = {}

            dbg_print(dbg_on, 'accuracy: %s' % evt_frame[len_parsed:])
            accuracy, len_field = get_accuracy(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            pos_arg['accuracy'] = accuracy
            msg_arg['accuracy'] = accuracy

            if mask & evt_mask_speed:
                dbg_print(dbg_on, 'speed: %s' % evt_frame[len_parsed:])
                speed, len_field = get_speed(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['speed'] = speed
                msg_arg['speed'] = speed

            if mask & evt_mask_heading:
                dbg_print(dbg_on, 'heading: %s' % evt_frame[len_parsed:])
                heading, len_field = get_heading(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['heading'] = heading
                msg_arg['heading'] = heading

            if mask & evt_mask_altitude:
                dbg_print(dbg_on, 'altitude: %s' % evt_frame[len_parsed:])
                altitude, len_field = get_altitude(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['altitude'] = altitude
                msg_arg['altitude'] = altitude

            dbg_print(dbg_on, 'longitude: %s' % evt_frame[len_parsed:])
            lng, len_field = get_lng(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            pos_arg['longitude'] = lng
            msg_arg['longitude'] = lng

            dbg_print(dbg_on, 'latitude: %s' % evt_frame[len_parsed:])
            lat, len_field = get_lat(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            pos_arg['latitude'] = lat
            msg_arg['latitude'] = lat

            dbg_print(dbg_on, 'gps_utc_t: %s' % evt_frame[len_parsed:])
            gps_tm, len_field = get_gps_time(asc_report, evt_frame[len_parsed-4:])
            len_parsed += len_field
            if len(gps_tm) != 14:
                gps_tm = '00000000000000'
            pos_arg['gps_utc_t'] = gps_tm
            msg_arg['gps_utc_t'] = gps_tm

            if mask & evt_mask_cell_id:
                dbg_print(dbg_on, 'mcc: %s' % evt_frame[len_parsed:])
                mcc, len_field = get_mcc(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['mcc'] = mcc
                msg_arg['mcc'] = mcc
                dbg_print(dbg_on, 'mnc: %s' % evt_frame[len_parsed:])
                mnc, len_field = get_mnc(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['mnc'] = mnc
                msg_arg['mnc'] = mnc
                dbg_print(dbg_on, 'lac: %s' % evt_frame[len_parsed:])
                lac, len_field = get_lac(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['lac'] = lac
                msg_arg['lac'] = lac
                dbg_print(dbg_on, 'cell_id: %s' % evt_frame[len_parsed:])
                cell_id, len_field = get_cell_id(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['cell_id'] = cell_id
                msg_arg['cell_id'] = cell_id
                dbg_print(dbg_on, 'ta: %s' % evt_frame[len_parsed:])
                ta, len_field = get_ta(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                pos_arg['ta'] = ta
                msg_arg['ta'] = ta
            positions.append(vars(Position(**pos_arg)))
        msg_arg['positions'] = [pos_arg]

        if mask & evt_mask_current_mileage:
            dbg_print(dbg_on, 'current_mileage: %s' % evt_frame[len_parsed:])
            print(len_parsed)
            print(evt_frame[len_parsed+4:])
            current_mileage, len_field = get_current_mileage(asc_report, evt_frame[len_parsed+4:])
            len_parsed += len_field
            msg_arg['current_mileage'] = current_mileage

        if mask & evt_mask_total_mileage:
            dbg_print(dbg_on, 'total_mileage: %s' % evt_frame[len_parsed:])
            total_mileage, len_field = get_total_mileage(asc_report, evt_frame[len_parsed+4:])
            len_parsed += len_field
            msg_arg['total_mileage'] = total_mileage

        if mask & evt_mask_current_hmc:
            dbg_print(dbg_on, 'current_hmc: %s' % evt_frame[len_parsed:])
            current_hmc, len_field = get_current_hmc(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['current_hmc'] = current_hmc

        if msg_arg['msg_type'] == 3:

            if mask & evt_mask_total_hmc  :
                dbg_print(dbg_on, 'total_hmc: %s' % evt_frame[len_parsed:])
                total_hmc,len_field = get_total_hmc(asc_report, evt_frame[len_parsed:])

                len_parsed += len_field

                msg_arg['total_hmc'] = 'skipped'
        else:
            print("else")
            print(msg_arg['msg_type'])
            if mask & evt_mask_total_hmc  :
                print("else")
                dbg_print(dbg_on, 'total_hmc: %s' % evt_frame[len_parsed:])
                total_hmc, len_field = get_total_hmc(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                msg_arg['total_hmc'] = total_hmc
        if msg_arg['msg_type'] == 3:
            if mask & evt_mask_send_t:
                send_t, len_field = get_send_time_hbd(asc_report, evt_frame[len_parsed-8:])
                len_parsed += len_field
                msg_arg['send_t'] = str(send_t)
        else :
            if mask & evt_mask_send_t:
                send_t, len_field = get_send_time_hbd(asc_report, evt_frame[len_parsed:])
                len_parsed += len_field
                msg_arg['send_t'] = str(send_t)

        if mask & evt_mask_cn:
            cn, len_field = get_count_number(asc_report, evt_frame[len_parsed:])
            len_parsed += len_field
            msg_arg['cn'] = cn
        else:
            msg_arg['cn'] = None
    except:
        print('-' * 40)
        print('Parse Hex EVT format error: ', sys.exc_info()[0])
        print(aschex_report)
        print(asc_report)
        traceback.print_exc()  # XXX But this goes to stderr!
        print('-' * 40)
    finally:
        print(msg_arg)
    return [msg_arg]

if __name__ == '__main__':
    x = parse_aschex_report_evt('2B4256540300FC17BF005D560200022A56332205064B090000004100010000000000000000000000000000000000000000000000024000010015019D9A0C000000000000036D0200000000000000000007E8050D0C2B24A84383540D0A')
    print(x)