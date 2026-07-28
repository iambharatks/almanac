class Solution {
public:
    int uniqueXorTriplets(vector<int>& nums) {
        int N = 5000;
        int maxV = *max_element(nums.begin(),nums.end());
        int msb = 1;
        while(msb <= maxV){
            msb <<= 1;
        }
        vector<bool> count(msb);
        vector<bool> nextC(msb);
        for(int i = 0 ; i < size(nums) ; i++){
           count[nums[i]] = 1;
           for(int j = 0 ; j < msb ; j++){
                if(count[j])
                    nextC[nums[i]^j] = 1;
           }
        }
        count = nextC;
        nextC = vector<bool>(msb);
        for(int i = 0 ; i < size(nums) ; i++){
           for(int j = 0 ; j < msb ; j++){
                if(count[j])
                    nextC[nums[i]^j] = 1;
           }
        }
        int res = 0;
        for(int i = 0 ; i < msb ; i++){
            res += nextC[i];
        }
        return res;
    }
};