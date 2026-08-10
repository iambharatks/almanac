class Solution {
public:
    vector<int> corpFlightBookings(vector<vector<int>>& bookings, int n) {
        vector<int> booked(n+1);
        for(auto &booking : bookings){
            booked[booking[0]-1] += booking[2];
            booked[booking[1]] -= booking[2];
        }
        for(int i = 1 ; i < n ; i++){
            booked[i] += booked[i-1];
        }
        return vector<int>(begin(booked),begin(booked)+n);
    }
};